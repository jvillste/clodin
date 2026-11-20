(ns examples.standalone-lib
  "A library namespace used by the standalone executable example. Its greet
  procedure is linked into the executable because examples.standalone-app
  calls it."
  (:require [clodin.compiler :refer [defproc extern]]))

(extern printf [:ptr] :i32 :varargs)
(extern strlen [:ptr] :i64)

(defproc greet [name :ptr] :i32
  (printf "hello, %s\n" name))

(defproc greet-length [name :ptr] :i64
  (strlen name))
