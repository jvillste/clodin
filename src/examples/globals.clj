(ns examples.globals
  "Top level mutable state shared between clodin and Clojure.

  (defvar running? :boolean true) declares a global. In clodin source the name
  reads as a value and (set running? false) writes it, both through atomic
  acquire/release accesses. In Clojure the same name is bound to a handle
  implementing clojure.lang.IDeref and clojure.lang.IAtom2, so @running?,
  (reset! running? false) and (swap! counter inc) work.

  A native loop blocks the JVM thread that entered it, so the write that stops
  it has to come from another thread. Run the loop in a future:

    (def worker (future (spin-until-stopped)))
    (Thread/sleep 100)
    (reset! running? false)
    @worker"
  (:require [clodin.compiler :refer [defproc defvar extern]]))

(extern printf [:ptr] :i32 :varargs)

(defvar running? :boolean true)
(defvar turns :i64 0)
(defvar scale :f64 1.5)

(defproc spin-until-stopped [] :i64
  (let [count :i64 0]
    (while running?
      (set count (+ count 1)))
    (set turns count)
    count))

(defproc bump-turns [amount :i64] :i64
  (let [next-value :i64 (+ turns amount)]
    (set turns next-value)
    next-value))

(defproc scaled-turns [] :f64
  (* (to-float turns) scale))

(defproc report []
  (printf "turns = %ld, scaled = %.2f\n" turns (scaled-turns)))

(comment
  (.start (Thread. (fn []
                     (spin-until-stopped)
                     (println! "spinning stopped"))))
  @running?
  (reset! running? false)
  (report)
  (bump-turns 10)
  (report)
  (reset! scale 2.0)
  (report))
