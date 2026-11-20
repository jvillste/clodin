(ns examples.globals
  "Top level mutable state shared between clodin and Clojure.

  (defvar running? :boolean true) declares a global. In clodin source the name
  reads as a value and (set running? false) writes it, both through atomic
  acquire/release accesses. In Clojure the same name is bound to a handle
  implementing clojure.lang.IDeref and clojure.lang.IAtom2, so @running?,
  (reset! running? false) and (swap! counter inc) work.

  A native loop blocks the JVM thread that entered it, so the write that stops
  it has to come from another thread. Run the loop on its own thread:

    (def worker (future (spin-until-stopped)))
    (Thread/sleep 100)
    (reset! running? false)
    @worker

  Printing uses (println ...) and (print ...), which call back into the JVM on
  the calling thread. A future conveys *out*, so a background loop prints to
  the REPL without any capture lock, and report below can run while the loop
  spins."
  (:require [clodin.compiler :refer [defproc defvar]]))

(defvar running? :boolean true)
(defvar turns :i64 0)
(defvar scale :f64 1.5)

(defproc spin-until-stopped [] :i64
  (let [count :i64 0]
    (while running?
      (set count (+ count 1))
      ;; publish progress so Clojure can read it while the loop runs
      (set turns count))
    count))

(defproc bump-turns [amount :i64] :i64
  (let [next-value :i64 (+ turns amount)]
    (set turns next-value)
    next-value))

(defproc scaled-turns [] :f64
  (* (to-float turns) scale))

(defproc report []
  (println "turns = " turns ", scaled = " (scaled-turns)))

(comment
  ;; Run the loop on its own thread, then read the shared state while it spins.
  (def worker (future (spin-until-stopped)))
  (Thread/sleep 100)
  (report)
  (reset! running? false)
  (reset! running? true)
  @turns
  @worker

  ;; Ordinary globals: native writes are visible to Clojure and vice versa.
  (bump-turns 10)
  (report)
  (reset! scale 2.0)
  (report))
