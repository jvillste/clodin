(ns clodin.compiler
  (:require [clojure.test :refer [deftest is]]))

(defproc subbstract-one [number :i64]
  (- number 1))

(defproc add-some [number :i64]
  (substract-one (+ number 42)))

(deftest test-add-some
  (is (= 42 (add-some 1))))
