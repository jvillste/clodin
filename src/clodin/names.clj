(ns clodin.names
  "Qualified names for clodin procedures and globals.

  Every procedure and global is registered under a qualified name,
  namespace/name, so two namespaces can define the same short name. The
  namespace is part of the native symbol too, ns.name, because the JIT looks
  symbols up by name and an unqualified symbol would collide the same way.

  A source reference resolves to exactly one qualified name. A symbol that
  already contains a slash is used as written, and an unqualified symbol
  refers to the namespace the reference appears in or, failing that, to
  clodin.core. There is no require, refer or alias support yet, so a name from
  any other namespace has to be written qualified.

  clodin.core holds procedures written in clodin itself, such as not, and
  they are visible unqualified everywhere. print and println are compiler
  forms, so they cannot live in a namespace."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]))

(def core-namespace
  "The namespace whose procedures and globals are visible unqualified
  everywhere."
  "clodin.core")

(defn qualified-name
  "The registry key for name defined in namespace, for example
  \"clodin.runtime/print-i64\"."
  [namespace name]
  (str namespace "/" name))

(deftest test-qualified-name
  (is (= "clodin.core/print" (qualified-name "clodin.core" "print")))
  (is (= "examples.spring/layout-step"
         (qualified-name "examples.spring" "layout-step"))))

(defn qualified-namespace
  "The namespace part of a qualified name, or nil when name has no slash."
  [qualified]
  (let [slash (string/index-of (str qualified) "/")]
    (when slash
      (subs (str qualified) 0 slash))))

(deftest test-qualified-namespace
  (is (= "clodin.core" (qualified-namespace "clodin.core/print")))
  (is (= "examples.spring"
         (qualified-namespace "examples.spring/layout-step")))
  (is (nil? (qualified-namespace "print"))))

(defn symbol-name
  "The native LLVM symbol for name defined in namespace.

  :qualified joins the namespace and the name with a dot, for example
  \"clodin.runtime.print-i64\", which a JIT build needs so two namespaces can
  define the same short name. :plain drops the namespace, which an offline
  build uses because the system linker exports the plain names and the entry
  point has to be exactly main.

  Generated symbols append a $ suffix to this symbol (foo$3, foo$sig2,
  counter$g1), and a short name may not contain a dot, so the qualified form
  cannot be read two ways."
  [namespace name style]
  (case style
    :qualified (str namespace "." name)
    :plain (str name)
    (throw (ex-info (str "Unknown symbol style: " style) {:style style}))))

(deftest test-symbol-name
  (is (= "clodin.runtime.print-i64"
         (symbol-name "clodin.runtime" "print-i64" :qualified)))
  (is (= "print-i64" (symbol-name "clodin.runtime" "print-i64" :plain)))
  (is (thrown? clojure.lang.ExceptionInfo
               (symbol-name "clodin.runtime" "print-i64" :mangled))))

(defn valid-short-name!
  "Throws unless name can be the short name of a procedure or global. '$' is
  reserved for generated symbols (foo$3, foo$sig2, counter$g1) and a dot
  separates the namespace from the name in a native symbol."
  [kind name]
  (doseq [reserved ["$" "."]]
    (when (string/includes? (str name) reserved)
      (throw (ex-info (str kind " names must not contain '" reserved "': " name)
                      {:kind kind :name name}))))
  name)

(deftest test-valid-short-name
  (is (= "print-i64" (valid-short-name! "Procedure" "print-i64")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not contain '\$'"
                        (valid-short-name! "Procedure" "print$2")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must not contain '\.'"
                        (valid-short-name! "Global" "a.b")))
  (testing "the message names the kind of definition"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Global names"
                          (valid-short-name! "Global" "a.b")))))

(defn resolve-name
  "Resolves a source symbol to a qualified name, given the namespace the
  reference appears in and a predicate that reports whether a qualified name
  is defined. Returns the qualified name, or nil when nothing matches.

  A symbol with a slash is already qualified. An unqualified symbol refers to
  the referring namespace, then to clodin.core. Nothing else is visible
  unqualified, which is why a helper procedure in another namespace has to be
  called by its qualified name."
  [referring-namespace symbol defined?]
  (let [symbol-namespace (namespace symbol)
        short-name (name symbol)]
    (if symbol-namespace
      (let [qualified (qualified-name symbol-namespace short-name)]
        (when (defined? qualified) qualified))
      (let [own (qualified-name referring-namespace short-name)
            core (qualified-name core-namespace short-name)]
        (cond
          (defined? own) own
          (defined? core) core
          :else nil)))))

(deftest test-resolve-name
  (let [defined #{ "my.ns/helper" "clodin.core/print" }]
    (testing "an unqualified name finds its own namespace first"
      (is (= "my.ns/helper" (resolve-name "my.ns" 'helper defined))))
    (testing "an unqualified name falls back to clodin.core"
      (is (= "clodin.core/print" (resolve-name "my.ns" 'print defined))))
    (testing "a qualified name is used as written"
      (is (= "my.ns/helper" (resolve-name "other.ns" 'my.ns/helper defined))))
    (testing "an unknown name resolves to nothing"
      (is (nil? (resolve-name "my.ns" 'missing defined)))
      (is (nil? (resolve-name "my.ns" 'other.ns/helper defined))))
    (testing "a name in another namespace is not visible unqualified"
      (is (nil? (resolve-name "other.ns" 'helper defined))))))
