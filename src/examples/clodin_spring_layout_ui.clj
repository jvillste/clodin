(ns examples.clodin-spring-layout-ui
  "Live visualization of the native spring layout: a Swing window that runs
  the clodin simulation on its own thread and draws the graph with Java2D on
  the EDT.

  The simulation thread runs `step-layout-native` against the back position
  buffer; every `steps-per-frame` native steps it copies the back buffer into
  the next of two front segments and swaps the front-atom to it, so the EDT
  always paints a complete, tearing-free snapshot of the layout. The arena is
  shared, so both threads may touch the segments (a single f32 load or store
  is atomic).

  Rendering is plain Clojure + Java2D: arrows and text need path stroking and
  a font/glyph rasterizer, which Java2D provides for free - clodin is only
  used for the physics."
  (:require [clodin.compiler :as compiler]
            [clojure.test :refer [deftest is testing]]
            [examples.clodin-spring-layout :as layout])
  (:import (java.awt
            BasicStroke
            Color
            Dimension
            RenderingHints)
           (java.awt.event ActionListener WindowAdapter)
           (java.awt.geom Ellipse2D$Double Line2D$Double Path2D$Double)
           (java.awt.image BufferedImage)
           (java.lang.foreign ValueLayout)
           (javax.swing
            JFrame
            JPanel
            SwingUtilities
            Timer)))

(def ^:private background-color (Color. 245 245 245))
(def ^:private edge-color (Color. 130 130 140))
(def ^:private node-color (Color. 66 133 244))
(def ^:private label-color (Color. 40 40 40))

(def ^:private example-graph
  [{:dependent :a1 :dependency :c2}
   {:dependent :b1 :dependency :a2}
   {:dependent :c1 :dependency :b2}])

;;; ---------------------------------------------------------------------------
;;; Rendering
;;; ---------------------------------------------------------------------------

(defn- node-position
  "Reads the [x y] of a node from a position buffer."
  [buffer node-index]
  [(.getAtIndex buffer ValueLayout/JAVA_FLOAT (* node-index 2))
   (.getAtIndex buffer ValueLayout/JAVA_FLOAT (inc (* node-index 2)))])

(defn- node-positions
  "Reads every node position from the buffer as a vector of [x y] pairs."
  [buffer node-count]
  (mapv (fn [node-index] (node-position buffer node-index)) (range node-count)))

(defn fit-transform
  "Returns [scale translate-x translate-y] mapping world coordinates to pixel
  coordinates (y axis flipped) so the bounding box of the positions fits the
  panel with the given padding."
  [panel-width panel-height positions padding]
  (let [xs (mapv first positions)
        ys (mapv second positions)
        min-x (apply min xs) max-x (apply max xs)
        min-y (apply min ys) max-y (apply max ys)
        span-x (max 1.0 (- max-x min-x))
        span-y (max 1.0 (- max-y min-y))
        scale (min (/ (- panel-width (* 2 padding)) span-x)
                   (/ (- panel-height (* 2 padding)) span-y))
        center-x (/ (+ min-x max-x) 2.0)
        center-y (/ (+ min-y max-y) 2.0)]
    [scale
     (- (/ panel-width 2.0) (* center-x scale))
     (+ (/ panel-height 2.0) (* center-y scale))]))

(defn- screen-position
  "Maps a world [x y] through a fit-transform to a pixel [x y]."
  [[scale translate-x translate-y] [x y]]
  [(+ translate-x (* x scale)) (- translate-y (* y scale))])

(defn- draw-arrow
  "Draws a line from (x1 y1) to (x2 y2) with an arrowhead at the target end."
  [graphics x1 y1 x2 y2]
  (let [dx (- x2 x1)
        dy (- y2 y1)
        length (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (when (> length 0.0)
      (let [unit-x (/ dx length)
            unit-y (/ dy length)
            arrow-length (min 18.0 (* 0.25 length))
            arrow-width (* 0.5 arrow-length)
            base-x (- x2 (* unit-x arrow-length))
            base-y (- y2 (* unit-y arrow-length))
            perp-x (- unit-y)
            perp-y unit-x]
        (.draw graphics (Line2D$Double. x1 y1 x2 y2))
        (.fill graphics
               (doto (Path2D$Double.)
                 (.moveTo x2 y2)
                 (.lineTo (+ base-x (* perp-x arrow-width)) (+ base-y (* perp-y arrow-width)))
                 (.lineTo (- base-x (* perp-x arrow-width)) (- base-y (* perp-y arrow-width)))
                 (.closePath)))))))

(defn- draw-node
  [graphics x y radius]
  (.fill graphics (Ellipse2D$Double. (- x radius) (- y radius) (* 2 radius) (* 2 radius))))

(defn paint-graph!
  "Paints the graph read from the front buffer of the state onto the given
  Graphics2D. The state map is {:vars [...] :front-atom <atom of segment>
  :node-count n :edges [[from-index to-index] ...]}. Extracted from the
  panel so it can be tested against an offscreen BufferedImage."
  [graphics width height {:keys [front-atom node-count vars edges]}]
  (let [positions (node-positions @front-atom node-count)]
    (when (seq positions)
      (let [padding 40.0
            [scale translate-x translate-y] (fit-transform width height positions padding)
            to-screen (fn [position] (screen-position [scale translate-x translate-y] position))]
        (doto graphics
          (.setRenderingHint RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
          (.setRenderingHint RenderingHints/KEY_TEXT_ANTIALIASING RenderingHints/VALUE_TEXT_ANTIALIAS_ON)
          (.setColor background-color)
          (.fillRect 0 0 width height))
        (.setColor graphics edge-color)
        (.setStroke graphics (BasicStroke. (float 1.5)))
        (doseq [[from-index to-index] edges
                :let [[from-x from-y] (to-screen (nth positions from-index))
                      [to-x to-y] (to-screen (nth positions to-index))]]
          (draw-arrow graphics from-x from-y to-x to-y))
        (.setColor graphics node-color)
        (doseq [[node-x node-y] positions
                :let [[screen-x screen-y] (to-screen [node-x node-y])
                      radius (max 4.0 (min 14.0 (* 0.35 scale)))]]
          (draw-node graphics screen-x screen-y radius))
        (.setColor graphics label-color)
        (doseq [[node-index [node-x node-y]] (map-indexed vector positions)
                :let [[screen-x screen-y] (to-screen [node-x node-y])]]
          (.drawString graphics (str (nth vars node-index))
                       (float (+ screen-x 8.0)) (float (- screen-y 8.0))))))))

(defn- graph-panel
  "A JPanel that paints the graph from the current front buffer."
  [state]
  (proxy [JPanel] []
    (paintComponent [graphics]
      (proxy-super paintComponent graphics)
      (paint-graph! graphics (.getWidth this) (.getHeight this) state))))

;;; ---------------------------------------------------------------------------
;;; Simulation thread and window
;;; ---------------------------------------------------------------------------

(defn- directed-edge-index-pairs
  "Returns the distinct [from-index to-index] pairs of the dependencies, with
  self-dependencies left out, in the order the dependencies are given."
  [dependency-graph vars]
  (let [index-by-var (into {} (map-indexed (fn [node-index var] [var node-index]) vars))]
    (->> dependency-graph
         (map (fn [{:keys [dependent dependency]}]
                [(get index-by-var dependent) (get index-by-var dependency)]))
         (distinct)
         (remove (fn [[from-index to-index]] (= from-index to-index)))
         (vec))))

(defn- start-simulation!
  "Runs the simulation on its own daemon thread: every steps-per-frame native
  steps, the back position buffer is copied into the next front segment and
  the front-atom is swapped to it, so the EDT always paints a complete
  snapshot. The thread stops when stop? is set or max-steps is reached. The
  arena is not closed here: it is freed when the JVM exits with the window."
  [state stop?]
  (let [{:keys [layout front-atom front-segments node-distance max-steps
                steps-per-frame fps]} state
        {:keys [positions displacements offsets indices node-count]} layout
        initial-temperature (* node-distance (Math/sqrt (double node-count)) 0.5)]
    (when (pos? node-count)
      (doto (Thread.
             (fn []
               (loop [step 0]
                 (if (or @stop? (>= step max-steps))
                   nil
                   (do
                     (dotimes [offset steps-per-frame]
                       (let [s (+ step offset)]
                         (when (< s max-steps)
                           (layout/step-layout-native positions displacements offsets indices
                                                      (long node-count) (float node-distance)
                                                      (float (* initial-temperature
                                                                (- 1.0 (/ (double s) max-steps))))))))
                     (let [target (nth front-segments (mod (quot step steps-per-frame) 2))]
                       (.copyFrom target positions)
                       (reset! front-atom target))
                     (Thread/sleep (long (Math/round (/ 1000.0 fps))))
                     (recur (+ step steps-per-frame)))))))
        (.setDaemon true)
        (.start)))))

(defn show
  "Opens a Swing window that runs the spring layout simulation live. The
  simulation runs on its own thread and the EDT repaints at the given fps.

  Options (all optional):
    :node-distance 10     resting distance between connected nodes
    :iterations 200       number of simulation steps
    :steps-per-frame 5    native steps between published snapshots
    :fps 30               repaint rate
    :width 900 :height 700
    :title \"Spring layout\"

  Returns the JFrame."
  [dependency-graph & [{:keys [node-distance iterations steps-per-frame fps
                               width height title]
                        :or {node-distance 10 iterations 200 steps-per-frame 5
                             fps 30 width 900 height 700 title "Spring layout"}}]]
  (let [{:keys [vars layout]} (layout/graph-layout dependency-graph
                                                   {:node-distance node-distance})
        segment-size (* 2 (:node-count layout) 4)
        front-segments [(.allocate (:arena layout) segment-size)
                        (.allocate (:arena layout) segment-size)]
        stop? (atom false)
        state {:vars vars
               :front-atom (atom nil)
               :front-segments front-segments
               :node-count (:node-count layout)
               :edges (directed-edge-index-pairs dependency-graph vars)
               :layout layout
               :node-distance node-distance
               :max-steps iterations
               :steps-per-frame steps-per-frame
               :fps fps}
        panel (graph-panel state)
        frame (doto (JFrame. title)
                (.setDefaultCloseOperation JFrame/DISPOSE_ON_CLOSE)
                (.setContentPane panel)
                (.setLocationRelativeTo nil))
        timer (Timer. (int (Math/round (/ 1000.0 fps)))
                      (proxy [ActionListener] []
                        (actionPerformed [_] (.repaint panel))))]
    ;; publish the initial circle before the window appears
    (.copyFrom (first front-segments) (:positions layout))
    (reset! (:front-atom state) (first front-segments))
    (doto panel (.setPreferredSize (Dimension. (int width) (int height))))
    (.addWindowListener frame
                        (proxy [WindowAdapter] []
                          (windowClosing [_]
                            (reset! stop? true)
                            (.stop timer))))
    (.start timer)
    (.pack frame)
    (.setVisible frame true)
    (start-simulation! state stop?)
    frame))

(defn -main
  [& _]
  (SwingUtilities/invokeLater #(show example-graph)))

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(deftest test-fit-transform
  (testing "centers the bounding box of the positions in the panel"
    (let [[scale translate-x translate-y] (fit-transform 200 100 [[0.0 0.0] [10.0 10.0]] 10)
          [screen-x screen-y] (screen-position [scale translate-x translate-y] [5.0 5.0])]
      (is (pos? scale))
      (is (< (Math/abs (- screen-x 100.0)) 1.0e-6))
      (is (< (Math/abs (- screen-y 50.0)) 1.0e-6)))))

(deftest test-paint-graph
  (testing "paints nodes, arrows and labels onto an offscreen image"
    (let [{:keys [vars layout]} (layout/graph-layout example-graph {:node-distance 10})
          front-atom (atom (.allocate (:arena layout) (* 2 (:node-count layout) 4)))
          state {:vars vars
                 :front-atom front-atom
                 :node-count (:node-count layout)
                 :edges (directed-edge-index-pairs example-graph vars)}
          image (BufferedImage. 400 300 BufferedImage/TYPE_INT_RGB)
          graphics (.createGraphics image)]
      (try
        (layout/run-simulation! layout 10 200)
        (.copyFrom @front-atom (:positions layout))
        (paint-graph! graphics 400 300 state)
        (is (some (fn [[x y]]
                    (not= (.getRGB background-color) (.getRGB image x y)))
                  (for [x (range 400) y (range 300)] [x y])))
        (finally
          (.dispose graphics)
          (.close (:arena layout)))))))


(comment
  (SwingUtilities/invokeLater #(show (clojure.edn/read-string (slurp "temp/mappa-dependency-graph.edn"))
                                     {:iterations 2000
                                      :fps 1}))

  (time (layout/layout-dependency-graph-native (clojure.edn/read-string (slurp "temp/mappa-dependency-graph.edn"))
                                               {:node-distance 10
                                                :iterations 5000}))
  (compiler/set-optimization-level! #_:none
                                    :O3)
  )
