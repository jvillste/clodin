(ns clodin.core
  "The procedures every clodin namespace sees unqualified, like clojure.core.

  They are written in clodin itself and loaded lazily by clodin.compiler on
  the first procedure definition, because they need the defproc macro from
  that namespace. A procedure here is ordinary clodin code, so it is compiled,
  reloaded and linked like any other."
  ;; A clodin procedure named not replaces clojure.core/not in this namespace.
  (:refer-clojure :exclude [not])
  (:require [clodin.compiler :refer [defproc]]))

(defproc not [value :boolean]
  (= false value))

(defproc is-null [value :ptr]
  (= value (to-pointer 0)))
