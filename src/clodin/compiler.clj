(ns clodin.compiler)

(defproc add-some [number :i64]
  (+ number 42))

(deftest test-add-some
  (is (= 43 (add-some 1))))
