(ns examples.standalone-app
  "Builds a standalone native executable with
  clodin.compiler/build-executable!.

  The build links every procedure in this namespace and in the namespaces it
  calls (here examples.standalone-lib), then hands the object file to cc. One
  of those namespaces must define a procedure named main.

  Build it from any Clojure process with:

    (clodin.compiler/build-executable! 'examples.standalone-app {:output \"hello\"})

  then run ./hello. Expected output:

    hello, standalone binary from an imported namespace"
  (:require [clodin.compiler :refer [defproc]]
            [examples.standalone-lib :as standalone-lib]))

(defproc main [argc :i32 argv :ptr] :i32
  (examples.standalone-lib/greet "standalone binary from an imported namespace")
  (to-i32 0))

(comment
  (clodin.compiler/build-executable! 'examples.standalone-app {:output "hello"})
  )
