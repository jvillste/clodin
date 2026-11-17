(ns examples.clojure-spring-layout-with-buffers
  "A pure Clojure spring layout that uses the same cache-friendly primitive
  buffer layout as `examples.clodin-spring-layout`, so the two can be timed
  against each other to measure the speed the native clodin compiler adds.

  Coordinates live in flat `float-array`s (2 floats per node, x at
  2*node and y at 2*node+1) and the neighbour lists in CSR form as flat
  `int-array`s -- no Clojure vectors or boxing in the hot loops. The hot
  O(n^2) repulsion is written as primitive `loop`/`recur` over `aget`/aset.
  This mirrors the memory layout of the native version (the working set is
  equally cache friendly), so any speed difference is from execution, not
  from smaller data."
  (:require [clodin.compiler :as compiler]
            [examples.clodin-spring-layout :as clodin]
            [examples.spring-layout :as spring-layout]
            [clojure.test :refer [deftest is testing]]))

(def ^:private minimum-distance 1.0e-9)

;;; ---------------------------------------------------------------------------
;;; Buffers
;;; ---------------------------------------------------------------------------

(defn- neighbour-csr
  "Returns the [offsets indices] primitive int-array CSR form of a neighbours
  vector-of-vectors, so the neighbours of node i are
  indices[offsets[i] .. offsets[i+1])."
  [neighbours]
  (let [counts (mapv count neighbours)
        offsets (reductions + 0 counts)
        offsets-array (int-array (inc (count neighbours)))
        indices-array (int-array (reduce + 0 counts))]
    (dotimes [node-index (inc (count neighbours))]
      (aset-int offsets-array node-index (nth offsets node-index)))
    (doseq [[node-index neighbour-indices] (map-indexed vector neighbours)
            [slot neighbour-index] (map-indexed vector neighbour-indices)]
      (aset-int indices-array (+ (nth offsets node-index) slot) neighbour-index))
    [offsets-array indices-array]))

(defn layout-buffers
  "Returns a map of primitive-array buffers for a neighbours vector-of-vectors,
  mirroring the layout map of `examples.clodin-spring-layout`:

  - :positions and :displacements are float arrays holding 2 floats per node,
  - :offsets and :indices are the int-array CSR neighbour structure,
  - :node-count is the number of nodes."
  [neighbours]
  (let [[offsets-array indices-array] (neighbour-csr neighbours)
        node-count (count neighbours)]
    {:positions (float-array (* 2 node-count))
     :displacements (float-array (* 2 node-count))
     :offsets offsets-array
     :indices indices-array
     :node-count node-count}))

(defn write-positions!
  "Stores the given vector of [x y] pairs into the positions buffer."
  [buffer positions]
  (let [positions-array (:positions buffer)]
    (doseq [[node-index [x y]] (map-indexed vector positions)]
      (aset-float positions-array (* 2 node-index) (float x))
      (aset-float positions-array (inc (* 2 node-index)) (float y)))))

(defn read-positions
  "Returns the positions stored in the positions buffer as a vector of
  [x y] pairs."
  [buffer]
  (let [^floats positions-array (:positions buffer)
        node-count (:node-count buffer)]
    (mapv (fn [node-index]
            [(aget positions-array (* 2 node-index))
             (aget positions-array (inc (* 2 node-index)))])
          (range node-count))))

;;; ---------------------------------------------------------------------------
;;; Simulation
;;; ---------------------------------------------------------------------------

(defn- repulsive-displacement!
  "Accumulates the displacement that the repulsion of all other nodes causes
  on the node at node-index into its two slots of the displacement buffer.
  The node repels itself by zero, so the loop can visit every node."
  [^floats positions ^floats displacements node-count node-distance node-index]
  (let [i (int node-index)
        n (int node-count)
        x (aget positions (* 2 i))
        y (aget positions (inc (* 2 i)))
        ideal-squared (* node-distance node-distance)
        minimum-squared (* minimum-distance minimum-distance)]
    (loop [j (int 0)
           sum-x 0.0
           sum-y 0.0]
      (if (< j n)
        (let [jx (* 2 j)
              diff-x (- x (aget positions jx))
              diff-y (- y (aget positions (inc jx)))
              distance-squared (+ (* diff-x diff-x) (* diff-y diff-y))
              scale (/ ideal-squared (max minimum-squared distance-squared))]
          (recur (inc j) (+ sum-x (* diff-x scale)) (+ sum-y (* diff-y scale))))
        (do (aset-float displacements (* 2 i) (float sum-x))
            (aset-float displacements (inc (* 2 i)) (float sum-y)))))))

(defn- attraction-displacement!
  "Adds the displacement that the attraction of the dependencies of the node
  at node-index causes on it to its two displacement slots, which must
  already hold the repulsion."
  [^floats positions ^floats displacements ^ints offsets ^ints indices node-index node-distance]
  (let [i (int node-index)
        x (aget positions (* 2 i))
        y (aget positions (inc (* 2 i)))
        start (aget offsets i)
        end (aget offsets (inc i))]
    (loop [slot (int start)
           sum-x (aget displacements (* 2 i))
           sum-y (aget displacements (inc (* 2 i)))]
      (if (< slot end)
        (let [neighbour-index (aget indices slot)
              diff-x (- x (aget positions (* 2 neighbour-index)))
              diff-y (- y (aget positions (inc (* 2 neighbour-index))))
              distance (max minimum-distance
                            (float (Math/sqrt (double (+ (* diff-x diff-x)
                                                         (* diff-y diff-y))))))
              scale (/ distance node-distance)]
          (recur (inc slot)
                 (- sum-x (* diff-x scale))
                 (- sum-y (* diff-y scale))))
        (do (aset-float displacements (* 2 i) (float sum-x))
            (aset-float displacements (inc (* 2 i)) (float sum-y)))))))

(defn step-layout!
  "One step of the simulation: computes the combined displacement of every
  node into the displacement buffer, then moves the nodes by their
  displacements limited by the temperature, all in place on the positions
  buffer."
  [buffer node-distance temperature]
  (let [^floats positions (get buffer :positions)
        ^floats displacements (get buffer :displacements)
        ^ints offsets (get buffer :offsets)
        ^ints indices (get buffer :indices)
        node-count (:node-count buffer)
        node-distance (double node-distance)
        temperature (double temperature)]
    (dotimes [node-index node-count]
      (repulsive-displacement! positions displacements node-count node-distance node-index)
      (attraction-displacement! positions displacements offsets indices node-index node-distance))
    (dotimes [node-index node-count]
      (let [i (int node-index)
            x (aget positions (* 2 i))
            y (aget positions (inc (* 2 i)))
            displacement-x (aget displacements (* 2 i))
            displacement-y (aget displacements (inc (* 2 i)))
            length (float (Math/sqrt (double (+ (* displacement-x displacement-x)
                                                (* displacement-y displacement-y)))))]
        (when-not (< length minimum-distance)
          (let [scale (/ (min length temperature) length)]
            (aset-float positions (* 2 i) (float (+ x (* displacement-x scale))))
            (aset-float positions (inc (* 2 i)) (float (+ y (* displacement-y scale))))))))))

(defn run-in-place!
  "Runs the given number of steps of the simulation in place on the positions
  buffer, with the temperature cooling linearly to zero. Returns nil (the
  buffer is mutated); use for timing, then read read-positions to get the
  result."
  [buffer node-distance iterations]
  (let [node-count (:node-count buffer)
        initial-temperature (* (double node-distance) (Math/sqrt (double node-count)) 0.5)
        iterations (long iterations)]
    (dotimes [iteration iterations]
      (step-layout! buffer node-distance
                    (* initial-temperature (- 1.0 (/ (double iteration) (double iterations))))))
    nil))

(defn run-simulation!
  "Runs the given number of steps of the simulation in place on the positions
  buffer and returns the final positions as a vector of [x y] pairs."
  [buffer node-distance iterations]
  (run-in-place! buffer node-distance iterations)
  (read-positions buffer))

;;; ---------------------------------------------------------------------------
;;; Whole-graph convenience
;;; ---------------------------------------------------------------------------

;; Mirrors the (private) graph plumbing of the pure and native example
;; namespaces so this one stays self-contained.

(defn- index-by-var
  [vars]
  (into {} (map-indexed (fn [node-index var] [var node-index]) vars)))

(defn- edge-index-pairs
  [dependency-graph index-by-var]
  (->> dependency-graph
       (map (fn [{:keys [dependent dependency]}]
              [(get index-by-var dependent) (get index-by-var dependency)]))
       (distinct)
       (remove (fn [[from-index to-index]] (= from-index to-index)))
       (vec)))

(defn- neighbours-by-node-index
  [edge-index-pairs node-count]
  (reduce (fn [neighbours [from-index to-index]]
            (-> neighbours
                (update from-index (fnil conj []) to-index)
                (update to-index (fnil conj []) from-index)))
          (vec (repeat node-count []))
          edge-index-pairs))

(defn graph-buffers
  "Builds the buffers for the given dependency graph with the initial circle
  positions written into the positions buffer, returning
  {:vars [...] :buffers <buffer map>}. Mirrors
  `examples.clodin-spring-layout/graph-layout`, so both implementations can
  be timed on identical data."
  [dependency-graph & [{:keys [node-distance] :or {node-distance 10}}]]
  (let [initial (spring-layout/layout-dependency-graph dependency-graph
                                                       {:node-distance node-distance
                                                        :iterations 0})
        vars (vec (keys initial))
        neighbours (neighbours-by-node-index (edge-index-pairs dependency-graph
                                                               (index-by-var vars))
                                             (count vars))
        buffers (layout-buffers neighbours)]
    (write-positions! buffers (mapv (fn [var] (let [{:keys [x y]} (get initial var)] [x y])) vars))
    {:vars vars :buffers buffers}))

;;; ---------------------------------------------------------------------------
;;; Benchmark against the native clodin implementation
;;; ---------------------------------------------------------------------------

(defn- random-dependency-graph
  "A random acyclic dependency graph: var i depends on edges-per-var of the
  earlier vars, so the undirected graph is connected-ish and interesting for
  a force layout."
  [node-count edges-per-var seed]
  (let [rng (java.util.Random. seed)
        vars (mapv (fn [i] (keyword (str "v" i))) (range node-count))]
    (vec (for [i (range node-count)
               _ (range edges-per-var)
               :when (pos? i)]
           {:dependent (nth vars i)
            :dependency (nth vars (.nextInt rng i))}))))

(defn- mean-time-ms
  "Returns the mean wall time in milliseconds of calling f over repetitions
  runs, using System/nanoTime."
  [f repetitions]
  (let [start (System/nanoTime)]
    (dotimes [_ repetitions] (f))
    (/ (- (System/nanoTime) start) 1.0e6 repetitions)))

(defn- single-run-ms
  "Returns the wall time in milliseconds of a single call to f."
  [f]
  (let [start (System/nanoTime)]
    (f)
    (/ (- (System/nanoTime) start) 1.0e6)))

(defn- simulation-runners
  "Returns [(fn native-run) (fn clojure-run)] each performing one full
  simulation of iterations steps on the same random graph, so the two
  implementations can be timed on identical data."
  [node-count edges-per-var iterations seed]
  (let [graph (random-dependency-graph node-count edges-per-var seed)
        native-layout (:layout (clodin/graph-layout graph))
        buffers (:buffers (graph-buffers graph))
        native-run (fn []
                     (clodin/run-simulation-native (:positions native-layout)
                                                   (:displacements native-layout)
                                                   (:offsets native-layout)
                                                   (:indices native-layout)
                                                   (long (:node-count native-layout))
                                                   (float 10.0)
                                                   (long iterations)))
        clojure-run (fn [] (run-in-place! buffers 10.0 iterations))]
    [native-run clojure-run]))

(defn benchmark
  "Times the fully-JIT-warmed Clojure-buffer and the native clodin
  implementations of the simulation on the same random graph and returns
  {:clojure-ms ... :clodin-ms ... :speedup ...}, where speedup is
  clojure-ms / clodin-ms.

  The JVM tier-compiles Clojure (interpreted -> C1 -> C2) only after enough
  invocations of the hot methods, so a short cold run understates (slows)
  the Clojure number and inflates the speedup. To make the Clojure side as
  JIT-optimised as it can be, benchmark first runs each implementation at
  max(300, iterations) steps once, discarding the result, before the
  measured repetitions - see jit-profile to watch the tier-up directly.

  Options: :node-count 200, :iterations 30, :edges-per-var 3,
  :repetitions 5, :seed 1."
  [& [{:keys [node-count iterations edges-per-var repetitions seed]
       :or {node-count 200 iterations 30 edges-per-var 3 repetitions 5 seed 1}}]]
  ;; heavy one-off warmup so the Clojure inner functions reach C2
  (let [[warm-native warm-clojure]
        (simulation-runners node-count edges-per-var (max 300 iterations) seed)]
    (warm-native)
    (warm-clojure))
  (let [[native-run clojure-run]
        (simulation-runners node-count edges-per-var iterations seed)]
    (native-run)
    (clojure-run)
    (let [clodin-ms (mean-time-ms native-run repetitions)
          clojure-ms (mean-time-ms clojure-run repetitions)]
      {:clojure-ms clojure-ms
       :clodin-ms clodin-ms
       :speedup (/ clojure-ms clodin-ms)})))

(defn jit-profile
  "Runs the simulation `runs` times and prints each run's wall time for both
  implementations, so the HotSpot JIT tier-up is visible: early runs are
  slower while the hot methods climb from interpreted bytecode through C1 to
  C2 (with inlining), then plateau. If the times keep falling across many
  runs, the JIT had not yet settled and earlier numbers understate the
  native advantage. Each 1000-iteration run alone invokes the O(n^2) inner
  function roughly node-count * iterations times, so C2 settles within the
  first run or two; what takes more runs is the outer callers and inlining.
  Returns a vector of per-run {:clojure-ms ... :clodin-ms ...}.

  Options: :node-count 400, :iterations 1000, :runs 20, :edges-per-var 3,
  :seed 1."
  [& [{:keys [node-count iterations runs edges-per-var seed]
       :or {node-count 400 iterations 1000 runs 20 edges-per-var 3 seed 1}}]]
  (let [[native-run clojure-run]
        (simulation-runners node-count edges-per-var iterations seed)]
    (mapv (fn [index]
            (let [clojure-ms (single-run-ms clojure-run)
                  clodin-ms (single-run-ms native-run)]
              (println (format "run %2d  clojure %8.2f ms  clodin %7.4f ms"
                               index clojure-ms clodin-ms))
              {:clojure-ms clojure-ms :clodin-ms clodin-ms}))
          (range runs))))

(defn benchmark-optimization-levels
  "Measures the native clodin simulation time at each LLVM optimization level
  (:none :O0 :O1 :O2 :O3), plus the Clojure-buffer implementation as a
  reference. For each level it sets
  clodin.compiler/set-optimization-level!, forces a recompile, then times
  `repetitions` runs of a fresh simulation at that level. Returns
  {:clojure-ms ... :levels {<level> {:clodin-ms ...} ...}}.

  Note: LLJIT's codegen optimization level only tunes the code-generation
  backend; the in-process LLVM IR pass pipeline is not run (LLVMRunPasses is
  not usable with the current jextract bindings)."
  [& [{:keys [node-count iterations edges-per-var repetitions seed]
       :or {node-count 200 iterations 30 edges-per-var 3 repetitions 5 seed 1}}]]
  (let [;; reference Clojure time (independent of the clodin level)
        [_ clojure-run] (simulation-runners node-count edges-per-var iterations seed)
        clojure-ms (mean-time-ms clojure-run repetitions)
        levels (into {}
                     (mapv (fn [level]
                             (compiler/set-optimization-level! level)
                             (let [[native-run _]
                                   (simulation-runners node-count edges-per-var iterations seed)]
                               (native-run) ; triggers the recompile at this level
                               (native-run) ; warm
                               [level {:clodin-ms (mean-time-ms native-run repetitions)}]))
                           [:none :O0 :O1 :O2 :O3]))]
    {:clojure-ms clojure-ms :levels levels}))

(defn -main
  [& _]
  (let [{:keys [clojure-ms clodin-ms speedup]} (benchmark)]
    (println (format "clojure (floats): %8.2f ms" clojure-ms))
    (println (format "clodin (native):  %8.3f ms" clodin-ms))
    (println (format "speedup:            %6.1f x" speedup))))

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(def ^:private example-graph
  [{:dependent :a1 :dependency :c2}
   {:dependent :b1 :dependency :a2}
   {:dependent :c1 :dependency :b2}])

(deftest test-csr-buffers
  (let [buffer (layout-buffers [[1 2] [0] [0]])]
    (is (= 3 (:node-count buffer)))
    (is (= [0 2 3 4]
           (mapv #(aget (:offsets buffer) %) (range 4))))
    (is (= [1 2 0 0]
           (mapv #(aget (:indices buffer) %) (range 4))))))

(deftest test-write-and-read-positions
  (let [buffer (layout-buffers [[] []])]
    (write-positions! buffer [[1.0 2.0] [3.5 -4.25]])
    (is (= [[1.0 2.0] [3.5 -4.25]] (read-positions buffer)))))

(defn- mean-distance
  "Returns the mean distance between the given pairs of vars in positions."
  [positions var-pairs]
  (if (empty? var-pairs)
    0.0
    (/ (reduce + (for [[first-var second-var] var-pairs]
                   (let [{first-x :x first-y :y} (get positions first-var)
                         {second-x :x second-y :y} (get positions second-var)]
                     (Math/sqrt (+ (* (- first-x second-x) (- first-x second-x))
                                   (* (- first-y second-y) (- first-y second-y)))))))
       (count var-pairs))))

(deftest test-run-simulation
  (testing "is deterministic"
    (is (= (run-simulation! (:buffers (graph-buffers example-graph)) 10 200)
           (run-simulation! (:buffers (graph-buffers example-graph)) 10 200))))
  (testing "returns finite positions for every var"
    (let [{:keys [vars buffers]} (graph-buffers example-graph)
          positions (run-simulation! buffers 10 200)]
      (is (= (count vars) (count positions)))
      (is (every? (fn [[x y]] (and (Double/isFinite (double x))
                                   (Double/isFinite (double y))))
                  positions))))
  (testing "places connected vars closer to each other than unconnected vars"
    (let [positions (into {}
                          (map-indexed (fn [node-index [x y]]
                                         [(nth (:vars (graph-buffers example-graph)) node-index)
                                          {:x (double x) :y (double y)}]))
                          (run-simulation! (:buffers (graph-buffers example-graph)) 10 200))]
      (is (< (mean-distance positions [[:a1 :c2] [:b1 :a2] [:c1 :b2]])
             (mean-distance positions [[:a1 :a2] [:a1 :b2] [:b1 :b2]]))))))