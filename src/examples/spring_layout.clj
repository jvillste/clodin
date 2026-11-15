(ns examples.spring-layout
  (:require
   [clojure.test :refer [deftest is testing]]))

(def ^:private minimum-distance
  1.0e-9)

(defn- dependency-graph-vars
  "returns the vars in the dependency graph in the order they first appear"
  [dependency-graph]
  (->> dependency-graph
       (mapcat (fn [{:keys [dependent dependency]}]
                 [dependency dependent]))
       (distinct)
       (vec)))

(deftest test-dependency-graph-vars
  (is (= [:c2 :a1 :a2 :b1 :b2 :c1]
         (dependency-graph-vars [{:dependent :a1
                                  :dependency :c2}
                                 {:dependent :b1
                                  :dependency :a2}
                                 {:dependent :c1
                                  :dependency :b2}])))

  (is (= [] (dependency-graph-vars []))))

(defn- index-by-var
  "returns a map from a var to the index of its node"
  [vars]
  (into {}
        (map-indexed (fn [node-index var] [var node-index]))
        vars))

(deftest test-index-by-var
  (is (= {:a 0 :b 1 :c 2}
         (index-by-var [:a :b :c]))))

(defn- edge-index-pairs
  "returns the distinct pairs of node indices that are connected by a
  dependency. Dependencies of a var to itself are left out, because they would
  not move the node anywhere."
  [dependency-graph index-by-var]
  (->> dependency-graph
       (map (fn [{:keys [dependent dependency]}]
              [(get index-by-var dependent)
               (get index-by-var dependency)]))
       (distinct)
       (remove (fn [[from-index to-index]]
                 (= from-index to-index)))
       (vec)))

(deftest test-edge-index-pairs
  (is (= [[0 3] [1 4] [2 5]]
         (edge-index-pairs [{:dependent :a1
                             :dependency :c2}
                            {:dependent :b1
                             :dependency :a2}
                            {:dependent :c1
                             :dependency :b2}]
                           (index-by-var [:a1 :b1 :c1 :c2 :a2 :b2]))))

  (testing "leaves out dependencies of a var to itself"
    (is (= [[0 1]]
           (edge-index-pairs [{:dependent :a
                               :dependency :a}
                              {:dependent :a
                               :dependency :b}]
                             (index-by-var [:a :b]))))))

(defn- neighbours-by-node-index
  "returns, for every node index, the indices of the nodes that it is connected
  to"
  [edge-index-pairs node-count]
  (reduce (fn [neighbours [from-index to-index]]
            (-> neighbours
                (update from-index (fnil conj []) to-index)
                (update to-index (fnil conj []) from-index)))
          (vec (repeat node-count []))
          edge-index-pairs))

(deftest test-neighbours-by-node-index
  (is (= [[1 2] [0] [0]]
         (neighbours-by-node-index [[0 1] [0 2]] 3)))

  (is (= [[] []]
         (neighbours-by-node-index [] 2))))

(defn- initial-positions
  "returns the starting positions of the nodes on a circle, so that the same
  graph always starts from the same layout"
  [node-count node-distance]
  (if (< node-count 2)
    (vec (repeat node-count [0.0 0.0]))
    (let [radius (/ (* node-distance (Math/sqrt node-count))
                    2.0)]
      (vec (for [node-index (range node-count)]
             (let [angle (* 2.0
                            Math/PI
                            (/ (double node-index)
                               node-count))]
               [(* radius (Math/cos angle))
                (* radius (Math/sin angle))]))))))

(deftest test-initial-positions
  (is (= [] (initial-positions 0 10)))
  (is (= [[0.0 0.0]] (initial-positions 1 10)))

  (testing "places every node at the same distance from the origin"
    (let [positions (initial-positions 5 10)
          distances (map (fn [[x y]] (Math/sqrt (+ (* x x) (* y y))))
                         positions)]
      (is (= 5 (count positions)))
      (is (every? (fn [distance] (< (Math/abs (- distance (first distances)))
                                    1.0e-9))
                  distances))))

  (testing "is deterministic"
    (is (= (initial-positions 4 10)
           (initial-positions 4 10)))))

(defn- repulsive-displacement
  "returns the displacement that the repulsion of all other nodes causes on the
  node at the given index. The nodes push each other away with a force that is
  inversely proportional to the distance between them."
  [positions node-distance node-index]
  (let [node-count (count positions)
        position (nth positions node-index)
        position-x (nth position 0)
        position-y (nth position 1)
        ideal-distance-squared (* node-distance node-distance)]
    (loop [other-index 0
           displacement-x 0.0
           displacement-y 0.0]
      (if (>= other-index node-count)
        [displacement-x displacement-y]
        (if (= other-index node-index)
          (recur (inc other-index) displacement-x displacement-y)
          (let [other-position (nth positions other-index)
                difference-x (- position-x (nth other-position 0))
                difference-y (- position-y (nth other-position 1))
                distance (max minimum-distance
                              (Math/sqrt (+ (* difference-x difference-x)
                                            (* difference-y difference-y))))
                scale (/ ideal-distance-squared
                         (* distance distance))]
            (recur (inc other-index)
                   (+ displacement-x (* difference-x scale))
                   (+ displacement-y (* difference-y scale)))))))))

(deftest test-repulsive-displacement
  (testing "pushes a node away from the other nodes"
    (is (= [-10.0 0.0]
           (repulsive-displacement [[0.0 0.0] [10.0 0.0]] 10 0)))
    (is (= [10.0 0.0]
           (repulsive-displacement [[0.0 0.0] [10.0 0.0]] 10 1))))

  (testing "pushes harder when the nodes are closer"
    (is (= [-20.0 0.0]
           (repulsive-displacement [[0.0 0.0] [5.0 0.0]] 10 0))))

  (testing "does not move a node that is alone"
    (is (= [0.0 0.0]
           (repulsive-displacement [[3.0 4.0]] 10 0)))))

(defn- attraction-displacement
  "returns the displacement that the attraction of the dependencies of the node
  at the given index causes on it. The nodes pull each other together with a
  force that is proportional to the distance between them."
  [positions neighbours node-distance node-index]
  (let [position (nth positions node-index)
        position-x (nth position 0)
        position-y (nth position 1)]
    (loop [remaining-neighbours (get neighbours node-index)
           displacement-x 0.0
           displacement-y 0.0]
      (if (empty? remaining-neighbours)
        [displacement-x displacement-y]
        (let [neighbour-position (nth positions (first remaining-neighbours))
              difference-x (- position-x (nth neighbour-position 0))
              difference-y (- position-y (nth neighbour-position 1))
              distance (max minimum-distance
                            (Math/sqrt (+ (* difference-x difference-x)
                                          (* difference-y difference-y))))
              scale (/ distance node-distance)]
          (recur (rest remaining-neighbours)
                 (- displacement-x (* difference-x scale))
                 (- displacement-y (* difference-y scale))))))))

(deftest test-attraction-displacement
  (testing "pulls a node towards its neighbours"
    (is (= [10.0 0.0]
           (attraction-displacement [[0.0 0.0] [10.0 0.0]] [[1] [0]] 10 0)))
    (is (= [-10.0 0.0]
           (attraction-displacement [[0.0 0.0] [10.0 0.0]] [[1] [0]] 10 1))))

  (testing "pulls harder when the nodes are further apart"
    (is (= [20.0 0.0]
           (attraction-displacement [[0.0 0.0] [10.0 0.0]] [[1] [0]] 5 0))))

  (testing "does not move a node without neighbours"
    (is (= [0.0 0.0]
           (attraction-displacement [[3.0 4.0]] [[]] 10 0)))))

(defn- apply-displacements
  "returns the positions moved by the displacements, limited to the given
  temperature so that the layout settles down instead of oscillating"
  [positions displacements temperature]
  (vec (map (fn [position displacement]
              (let [displacement-x (nth displacement 0)
                    displacement-y (nth displacement 1)
                    length (Math/sqrt (+ (* displacement-x displacement-x)
                                         (* displacement-y displacement-y)))]
                (if (< length minimum-distance)
                  position
                  (let [limited-length (min length temperature)]
                    [(+ (nth position 0)
                        (* (/ displacement-x length) limited-length))
                     (+ (nth position 1)
                        (* (/ displacement-y length) limited-length))]))))
            positions
            displacements)))

(deftest test-apply-displacements
  (testing "moves the nodes by their displacements"
    (is (= [[1.0 2.0]]
           (apply-displacements [[0.0 0.0]] [[1.0 2.0]] 10))))

  (testing "limits the length of the displacement to the temperature"
    (is (= [[3.0 4.0]]
           (apply-displacements [[0.0 0.0]] [[6.0 8.0]] 5))))

  (testing "does not move a node without displacement"
    (is (= [[3.0 4.0]]
           (apply-displacements [[3.0 4.0]] [[0.0 0.0]] 5)))))

(defn- step-layout
  "returns the positions after one step of the simulation, where every node is
  moved by the sum of the repulsion of all other nodes and the attraction of
  its dependencies"
  [positions neighbours node-distance temperature]
  (apply-displacements
   positions
   (vec (for [node-index (range (count positions))]
          (let [[repulsion-x repulsion-y] (repulsive-displacement positions
                                                                  node-distance
                                                                  node-index)
                [attraction-x attraction-y] (attraction-displacement positions
                                                                     neighbours
                                                                     node-distance
                                                                     node-index)]
            [(+ repulsion-x attraction-x)
             (+ repulsion-y attraction-y)])))
   temperature))

(deftest test-step-layout
  (testing "pulls two connected nodes together when they are further apart than the node distance"
    (let [[[first-x _] _] (step-layout [[0.0 0.0]
                                       [100.0 0.0]]
                                      [[1] [0]]
                                      10
                                      1000)]
      (is (pos? first-x))))

  (testing "pushes two connected nodes apart when they are closer than the node distance"
    (let [[[first-x _] _] (step-layout [[0.0 0.0]
                                       [1.0 0.0]]
                                      [[1] [0]]
                                      10
                                      1000)]
      (is (neg? first-x)))))

(defn- run-simulation
  "returns the positions after the given number of simulation steps. The
  temperature starts high so that the nodes can find their places and cools
  down linearly to zero."
  [positions neighbours node-distance iterations]
  (let [initial-temperature (/ (* node-distance
                                  (Math/sqrt (count positions)))
                               2.0)]
    (loop [iteration 0
           positions positions]
      (if (>= iteration iterations)
        positions
        (recur (inc iteration)
               (step-layout positions
                            neighbours
                            node-distance
                            (* initial-temperature
                               (- 1.0
                                  (/ (double iteration)
                                     iterations)))))))))

(defn layout-dependency-graph
  "Uses simulated springs, to return coordinates for the nodes in a
  given dependency graph so that dependents and their dependants are
  given distance away from each other."
  [dependency-graph & [{:keys [node-distance iterations]
                        :or {node-distance 10
                             iterations 200}}]]
  (let [vars (dependency-graph-vars dependency-graph)
        node-count (count vars)
        neighbours (neighbours-by-node-index (edge-index-pairs dependency-graph
                                                               (index-by-var vars))
                                             node-count)
        positions (run-simulation (initial-positions node-count node-distance)
                                  neighbours
                                  node-distance
                                  iterations)]
    (into {}
          (map-indexed (fn [node-index position]
                         [(nth vars node-index)
                          {:x (nth position 0)
                           :y (nth position 1)}]))
          positions)))

(defn- distance-between
  [{:keys [x x-other y y-other]}]
  (Math/sqrt (+ (* (- x x-other)
                   (- x x-other))
                (* (- y y-other)
                   (- y y-other)))))

(defn- mean-distance
  "returns the mean distance between the given pairs of vars"
  [positions var-pairs]
  (if (empty? var-pairs)
    0.0
    (let [distances (for [[first-var second-var] var-pairs]
                      (let [first-position (get positions first-var)
                            second-position (get positions second-var)]
                        (distance-between {:x (:x first-position)
                                           :x-other (:x second-position)
                                           :y (:y first-position)
                                           :y-other (:y second-position)})))]
      (/ (reduce + distances)
         (count distances)))))

(deftest test-mean-distance
  (is (= 0.0 (mean-distance {} [])))
  (is (= 5.0 (mean-distance {:a {:x 0 :y 0}
                             :b {:x 3 :y 4}}
                            [[:a :b]]))))

(deftest test-layout-dependency-graph
  (let [dependency-graph [{:dependent :a1
                           :dependency :c2}
                          {:dependent :b1
                           :dependency :a2}
                          {:dependent :c1
                           :dependency :b2}]
        positions (layout-dependency-graph dependency-graph
                                           {:node-distance 10})]

    (testing "returns a position for every var in the graph"
      (is (= #{:a1 :b1 :c1 :a2 :b2 :c2}
             (set (keys positions)))))

    (testing "returns finite coordinates"
      (is (every? (fn [{:keys [x y]}]
                    (and (Double/isFinite x)
                         (Double/isFinite y)))
                  (vals positions))))

    (testing "returns the same layout for the same graph"
      (is (= positions
             (layout-dependency-graph dependency-graph
                                      {:node-distance 10}))))

    (testing "leaves the nodes on the starting circle without iterations"
      (is (= (into {}
                   (map-indexed (fn [node-index [x y]]
                                  [(nth (dependency-graph-vars dependency-graph)
                                        node-index)
                                   {:x x :y y}]))
                   (initial-positions (count (dependency-graph-vars dependency-graph))
                                      10))
             (layout-dependency-graph dependency-graph
                                      {:node-distance 10
                                       :iterations 0}))))

    (testing "spreads the nodes further apart with a larger node distance"
      (let [wider-positions (layout-dependency-graph dependency-graph
                                                     {:node-distance 20})]
        (is (< (mean-distance positions
                              [[:a1 :c2] [:b1 :a2] [:c1 :b2]])
               (mean-distance wider-positions
                              [[:a1 :c2] [:b1 :a2] [:c1 :b2]])))))

    (testing "places connected vars closer to each other than unconnected vars"
      (is (< (mean-distance positions
                            [[:a1 :c2] [:b1 :a2] [:c1 :b2]])
             (mean-distance positions
                            [[:a1 :a2] [:a1 :b2] [:b1 :b2]]))))))

(deftest test-layout-dependency-graph-without-dependencies
  (testing "returns the starting positions when there are no dependencies"
    (is (= {}
           (layout-dependency-graph [])))

    (is (= {:a {:x 0.0 :y 0.0}}
           (layout-dependency-graph [{:dependent :a
                                      :dependency :a}])))))
