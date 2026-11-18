(ns clodin.repl
  "REPL helpers for clodin.

  Kept as a compatibility alias for clodin.native-stdout. The defproc wrappers
  already capture native stdout automatically, so this namespace is only
  needed when calling invoke-procedure directly or when capturing a block of
  calls."
  (:require [clodin.native-stdout :as native-stdout]))

(def capture-native-stdout
  "See clodin.native-stdout/capture-native-stdout."
  native-stdout/capture-native-stdout)

(defmacro with-native-stdout
  "See clodin.native-stdout/with-native-stdout."
  [& body]
  `(native-stdout/with-native-stdout ~@body))
