(ns examples.hello-clodin
  (:require [clodin.compiler :refer [defproc extern]]
            [clodin.inspect :as inspect]))

(extern printf [:ptr] :i32 :varargs)

(defproc main [argc :i32 argv :ptr] :i32
  (printf "Hello from Clodin!")
  (to-i32 0))

(comment
  (clodin.compiler/build-executable! *ns* {:output "hello"})

  (inspect/print-annotated-proc-assembly! "main")
  )
