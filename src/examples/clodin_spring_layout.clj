(ns examples.clodin-spring-layout
  "Native counterpart of the spring layout example. The physics of
  `examples.spring-layout` is ported to clodin procedures that operate on
  caller-owned buffers, so the hot loop runs as native code inside the
  LLJIT module and the JVM only crosses the boundary once per simulation
  run.

  The coordinates are 32 bit floats, halving the coordinate buffer compared
  with the f64 reference and letting LLVM pack twice as many lanes per SIMD
  vector. A layout is a set of memory segments in a shared arena: positions
  and a displacement scratch buffer hold 2 f32s per node (x at index
  2*node, y at 2*node+1), and the neighbour lists live in CSR form
  (offsets + indices). See `layout-buffers`."
  (:require [clodin.compiler :refer [defproc]]
            [examples.spring-layout :as spring-layout]
            [clojure.test :refer [deftest is testing]])
  (:import (java.lang.foreign Arena ValueLayout)))

(def ^:private minimum-distance
  "Mirrors examples.spring-layout/minimum-distance; used to avoid dividing
  by zero when two nodes are on top of each other."
  1.0e-9)

;;; ---------------------------------------------------------------------------
;;; Buffer ergonomics
;;; ---------------------------------------------------------------------------

(defn- write-csr!
  "Fills the offsets and indices buffers of a layout from a neighbours
  vector-of-vectors, so that the neighbours of node i are
  indices[offsets[i] .. offsets[i+1])."
  [layout neighbours]
  (let [counts (mapv count neighbours)
        offsets (reductions + 0 counts)]
    (dotimes [node-index (inc (count neighbours))]
      (.setAtIndex (:offsets layout) ValueLayout/JAVA_LONG node-index
                   (long (nth offsets node-index))))
    (doseq [[node-index neighbour-indices] (map-indexed vector neighbours)
            [offset-in-node neighbour-index] (map-indexed vector neighbour-indices)]
      (.setAtIndex (:indices layout) ValueLayout/JAVA_LONG
                   (+ (long (nth offsets node-index)) offset-in-node)
                   (long neighbour-index)))))

(defn layout-buffers
  "Allocates the shared arena and the memory segments for a native
  simulation of the given neighbours (a vector with, for every node, the
  vector of its neighbour node indices):

  - :positions and :displacements hold 2 f32s per node,
  - :offsets (node-count + 1 i64s) and :indices (one i64 per neighbour)
    hold the neighbours in CSR form.

  The arena must be closed by the caller when the simulation is done."
  [neighbours]
  (let [node-count (count neighbours)
        neighbour-count (transduce (map count) + 0 neighbours)
        arena (Arena/ofShared)
        layout {:arena arena
                :node-count node-count
                :positions (.allocate arena (* 2 node-count 4))
                :displacements (.allocate arena (* 2 node-count 4))
                :offsets (.allocate arena (* (inc node-count) 8))
                :indices (.allocate arena (* neighbour-count 8))}]
    (write-csr! layout neighbours)
    layout))

(defn write-positions!
  "Stores the given vector of [x y] pairs into the positions buffer of the
  layout."
  [layout positions]
  (doseq [[node-index [x y]] (map-indexed vector positions)]
    (.setAtIndex (:positions layout) ValueLayout/JAVA_FLOAT (* node-index 2) (float x))
    (.setAtIndex (:positions layout) ValueLayout/JAVA_FLOAT (inc (* node-index 2)) (float y))))

(defn read-positions
  "Returns the positions stored in the positions buffer of the layout as a
  vector of [x y] pairs."
  [layout]
  (vec (for [node-index (range (:node-count layout))]
         [(.getAtIndex (:positions layout) ValueLayout/JAVA_FLOAT (* node-index 2))
          (.getAtIndex (:positions layout) ValueLayout/JAVA_FLOAT (inc (* node-index 2)))])))

;;; ---------------------------------------------------------------------------
;;; Native simulation
;;; ---------------------------------------------------------------------------

(defproc repulsive-displacement!
  ;; Writes the displacement that the repulsion of all other nodes causes on
  ;; the node at node-index into its two slots of the displacement-scratch
  ;; buffer. The node repels itself by zero (the displacement is multiplied
  ;; by a zero difference), so every node can be visited in one loop.
  [positions :ptr displacement-scratch :ptr node-count :i64 node-distance :f32 node-index :i64]
  (let [x :f32 (buffer-get positions :f32 (* node-index 2))
        y :f32 (buffer-get positions :f32 (+ (* node-index 2) 1))
        ideal-squared :f32 (* node-distance node-distance)
        other-index :i64 0
        displacement-x :f32 (to-f32 0)
        displacement-y :f32 (to-f32 0)]
    (while (< other-index node-count)
      (let [other-x :f32 (buffer-get positions :f32 (* other-index 2))
            other-y :f32 (buffer-get positions :f32 (+ (* other-index 2) 1))
            difference-x :f32 (- x other-x)
            difference-y :f32 (- y other-y)
            distance-squared :f32 (+ (* difference-x difference-x)
                                     (* difference-y difference-y))
            distance :f32 (max (to-f32 1.0e-9) (sqrt distance-squared))
            scale :f32 (/ ideal-squared (* distance distance))]
        (set displacement-x (+ displacement-x (* difference-x scale)))
        (set displacement-y (+ displacement-y (* difference-y scale)))
        (set other-index (+ other-index 1))))
    (buffer-set displacement-scratch :f32 (* node-index 2) displacement-x)
    (buffer-set displacement-scratch :f32 (+ (* node-index 2) 1) displacement-y)))

(defproc attraction-displacement!
  ;; Adds the displacement that the attraction of the dependencies of the node
  ;; at node-index causes on it to its two slots of the displacement-scratch
  ;; buffer, which must already contain the repulsion displacement.
  [positions :ptr displacement-scratch :ptr offsets :ptr indices :ptr node-index :i64 node-distance :f32]
  (let [x :f32 (buffer-get positions :f32 (* node-index 2))
        y :f32 (buffer-get positions :f32 (+ (* node-index 2) 1))
        neighbour-start :i64 (buffer-get offsets :i64 node-index)
        neighbour-end :i64 (buffer-get offsets :i64 (+ node-index 1))
        neighbour-slot :i64 neighbour-start
        displacement-x :f32 (buffer-get displacement-scratch :f32 (* node-index 2))
        displacement-y :f32 (buffer-get displacement-scratch :f32 (+ (* node-index 2) 1))]
    (while (< neighbour-slot neighbour-end)
      (let [neighbour-index :i64 (buffer-get indices :i64 neighbour-slot)
            neighbour-x :f32 (buffer-get positions :f32 (* neighbour-index 2))
            neighbour-y :f32 (buffer-get positions :f32 (+ (* neighbour-index 2) 1))
            difference-x :f32 (- x neighbour-x)
            difference-y :f32 (- y neighbour-y)
            distance :f32 (max (to-f32 1.0e-9)
                               (sqrt (+ (* difference-x difference-x)
                                        (* difference-y difference-y))))
            scale :f32 (/ distance node-distance)]
        (set displacement-x (- displacement-x (* difference-x scale)))
        (set displacement-y (- displacement-y (* difference-y scale)))
        (set neighbour-slot (+ neighbour-slot 1))))
    (buffer-set displacement-scratch :f32 (* node-index 2) displacement-x)
    (buffer-set displacement-scratch :f32 (+ (* node-index 2) 1) displacement-y)))

(defproc step-layout-native
  ;; One step of the simulation: computes the combined displacement of every
  ;; node into the displacement-scratch buffer, then moves the nodes by their
  ;; displacements limited to the temperature, so that the layout settles
  ;; down instead of oscillating.
  [positions :ptr displacement-scratch :ptr offsets :ptr indices :ptr node-count :i64 node-distance :f32 temperature :f32]
  (let [node-index :i64 0]
    (while (< node-index node-count)
      (repulsive-displacement! positions displacement-scratch node-count node-distance node-index)
      (attraction-displacement! positions displacement-scratch offsets indices node-index node-distance)
      (set node-index (+ node-index 1))))
  (let [node-index :i64 0]
    (while (< node-index node-count)
      (let [x :f32 (buffer-get positions :f32 (* node-index 2))
            y :f32 (buffer-get positions :f32 (+ (* node-index 2) 1))
            displacement-x :f32 (buffer-get displacement-scratch :f32 (* node-index 2))
            displacement-y :f32 (buffer-get displacement-scratch :f32 (+ (* node-index 2) 1))
            length :f32 (sqrt (+ (* displacement-x displacement-x)
                                 (* displacement-y displacement-y)))]
        (buffer-set positions :f32 (* node-index 2)
                    (if (< length (to-f32 1.0e-9))
                      x
                      (+ x (* (/ displacement-x length) (min length temperature)))))
        (buffer-set positions :f32 (+ (* node-index 2) 1)
                    (if (< length (to-f32 1.0e-9))
                      y
                      (+ y (* (/ displacement-y length) (min length temperature)))))
        (set node-index (+ node-index 1))))))

(defproc run-simulation-native
  ;; Runs the given number of steps of the spring layout simulation in place
  ;; on the positions buffer. The temperature starts high so that the nodes
  ;; can find their places and cools down linearly to zero. Returns nothing;
  ;; read the positions buffer afterwards.
  [positions :ptr displacement-scratch :ptr offsets :ptr indices :ptr node-count :i64 node-distance :f32 iterations :i64]
  (let [initial-temperature :f32 (/ (* node-distance (sqrt (to-f32 node-count))) (to-f32 2))
        iteration :i64 0]
    (while (< iteration iterations)
      (let [temperature :f32 (* initial-temperature
                                (- (to-f32 1) (/ (to-f32 iteration) (to-f32 iterations))))]
        (step-layout-native positions displacement-scratch offsets indices node-count node-distance temperature))
      (set iteration (+ iteration 1)))))

(defn run-simulation!
  "Runs iterations of the native spring layout simulation in place on the
  positions buffer of the layout and returns the final positions as a vector
  of [x y] pairs."
  [layout node-distance iterations]
  (run-simulation-native (:positions layout) (:displacements layout)
                         (:offsets layout) (:indices layout)
                         (long (:node-count layout))
                         (float node-distance) (long iterations))
  (read-positions layout))

;;; ---------------------------------------------------------------------------
;;; Whole-graph convenience API
;;; ---------------------------------------------------------------------------

;; The helpers below mirror the (private) graph plumbing of
;; `examples.spring-layout` so this namespace stays self-contained; the pure
;; implementation is only used for the starting circle positions and for the
;; verification test.

(defn- index-by-var
  [vars]
  (into {} (map-indexed (fn [node-index var] [var node-index]) vars)))

(defn- edge-index-pairs
  [dependency-graph index-by-var]
  (->> dependency-graph
       (map (fn [{:keys [dependent dependency]}]
              [(get index-by-var dependent)
               (get index-by-var dependency)]))
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

(defn graph-layout
  "Allocates a layout for the given dependency graph: the shared arena, the
  position and displacement scratch buffers, the CSR neighbour buffers, and
  the initial circle positions written into the positions buffer. Returns
  {:vars [...] :layout <layout>} where layout is the map from
  layout-buffers. The arena stays open until the caller closes it, so this
  can be used for live simulations."
  [dependency-graph & [{:keys [node-distance] :or {node-distance 10}}]]
  (let [initial (spring-layout/layout-dependency-graph dependency-graph
                                                       {:node-distance node-distance
                                                        :iterations 0})
        vars (vec (keys initial))
        neighbours (neighbours-by-node-index (edge-index-pairs dependency-graph
                                                               (index-by-var vars))
                                             (count vars))
        initial-positions (mapv (fn [var] (let [{:keys [x y]} (get initial var)] [x y])) vars)
        layout (layout-buffers neighbours)]
    (write-positions! layout initial-positions)
    {:vars vars :layout layout}))

(defn layout-dependency-graph-native
  "Native counterpart of `examples.spring-layout/layout-dependency-graph`:
  returns a map from every var of the dependency graph to {:x ... :y ...}
  after the given number of spring layout steps, computed by the clodin
  procedures on a single f32 coordinate buffer. The initial circle positions
  come from the pure implementation (which returns them for zero iterations),
  so both simulations start from the same place."
  [dependency-graph & [{:keys [node-distance iterations]
                        :or {node-distance 10 iterations 200}}]]
  (let [{:keys [vars layout]} (graph-layout dependency-graph
                                           {:node-distance node-distance})]
    (try
      (run-simulation! layout node-distance iterations)
      (into {}
            (map-indexed (fn [node-index [x y]]
                           [(nth vars node-index) {:x x :y y}]))
            (read-positions layout))
      (finally
        (.close (:arena layout))))))

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(deftest test-layout-buffers-csr
  (let [layout (layout-buffers [[1 2] [0] [0]])]
    (try
      (is (= 3 (:node-count layout)))
      (is (= [0 2 3 4]
             (mapv (fn [node-index]
                     (.getAtIndex (:offsets layout) ValueLayout/JAVA_LONG node-index))
                   (range 4))))
      (is (= [1 2 0 0]
             (mapv (fn [neighbour-slot]
                     (.getAtIndex (:indices layout) ValueLayout/JAVA_LONG neighbour-slot))
                   (range 4))))
      (finally
        (.close (:arena layout))))))

(deftest test-write-and-read-positions
  (let [layout (layout-buffers [[] []])]
    (try
      (write-positions! layout [[1.0 2.0] [3.5 -4.25]])
      (is (= [[1.0 2.0] [3.5 -4.25]] (read-positions layout)))
      (finally
        (.close (:arena layout))))))

(def ^:private example-graph
  [{:dependent :a1 :dependency :c2}
   {:dependent :b1 :dependency :a2}
   {:dependent :c1 :dependency :b2}])

(def ^:private star-graph
  [{:dependent :leaf1 :dependency :hub}
   {:dependent :leaf2 :dependency :hub}
   {:dependent :leaf3 :dependency :hub}])

(def ^:private chain-graph
  [{:dependent :b :dependency :a}
   {:dependent :c :dependency :b}
   {:dependent :d :dependency :c}])

(defn- position-distance
  [{first-x :x first-y :y} {second-x :x second-y :y}]
  (Math/sqrt (+ (* (- first-x second-x) (- first-x second-x))
                (* (- first-y second-y) (- first-y second-y)))))

(defn- mean-distance
  "Returns the mean distance between the given pairs of vars in positions."
  [positions var-pairs]
  (if (empty? var-pairs)
    0.0
    (/ (reduce + (for [[first-var second-var] var-pairs]
                   (position-distance (get positions first-var)
                                      (get positions second-var))))
       (count var-pairs))))

(def ^:private verification-tolerance 0.5)

(def ^:private all-example-graphs
  [example-graph star-graph chain-graph
   [{:dependent :a :dependency :a}] []])

(deftest test-native-matches-pure
  (testing "the native simulation starts from the same circle as the pure one"
    (doseq [dependency-graph all-example-graphs]
      (let [native (layout-dependency-graph-native dependency-graph
                                                   {:node-distance 10
                                                    :iterations 0})
            pure (spring-layout/layout-dependency-graph dependency-graph
                                                        {:node-distance 10
                                                         :iterations 0})]
        (is (= (set (keys pure)) (set (keys native))))
        (doseq [[var pure-position] pure]
          (is (< (position-distance pure-position (get native var)) 1.0e-4))))))

  (testing "the native and pure layouts have the same shape after cooling"
    ;; f32 rounding breaks the symmetric graphs' symmetry at intermediate
    ;; steps (the chain and star have shallow modes), so compare the
    ;; rotation/translation-invariant pairwise distances at full cooling
    ;; instead of point-wise positions.
    (doseq [dependency-graph all-example-graphs]
      (let [native (layout-dependency-graph-native dependency-graph {:node-distance 10})
            pure (spring-layout/layout-dependency-graph dependency-graph {:node-distance 10})
            vars (vec (keys pure))]
        (is (= (set vars) (set (keys native))))
        (doseq [[first-index first-var] (map-indexed vector vars)
                second-index (range (inc first-index) (count vars))
                :let [second-var (nth vars second-index)
                      distance-difference (Math/abs
                                           (- (position-distance (get native first-var)
                                                                 (get native second-var))
                                              (position-distance (get pure first-var)
                                                                 (get pure second-var))))]]
          (is (< distance-difference verification-tolerance)
              (str "distance between " first-var " and " second-var " differs")))))))

(deftest test-native-is-deterministic
  (is (= (layout-dependency-graph-native example-graph {:node-distance 10})
         (layout-dependency-graph-native example-graph {:node-distance 10}))))

(deftest test-native-layout-quality
  (testing "places connected vars closer to each other than unconnected vars"
    (let [positions (layout-dependency-graph-native example-graph {:node-distance 10})]
      (is (< (mean-distance positions [[:a1 :c2] [:b1 :a2] [:c1 :b2]])
             (mean-distance positions [[:a1 :a2] [:a1 :b2] [:b1 :b2]]))))))
