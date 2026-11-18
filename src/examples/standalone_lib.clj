(ns examples.standalone-lib
  "A library namespace used by the standalone executable example. Its greet
  procedure is linked into the executable because examples.standalone-app
  calls it."
  (:require [clodin.compiler :refer [defproc extern]]))

(extern printf [:ptr] :i32 :varargs)

(defproc greet [name :ptr]
  (printf "hello, %s\n" name))
