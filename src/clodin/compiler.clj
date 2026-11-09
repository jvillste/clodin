(ns clodin.compiler
  "A low level odin-like language hosted in clojure.

  Clodin source code is written as clojure data structures. The
  defproc macro compiles a procedure into llvm ir, builds the whole
  accumulated module into a native shared library with clang, loads
  the library with project panama, and defines a clojure function of
  the same name that downcalls into the native procedure.

  Native procedures call each other with direct llvm calls inside the
  shared library, so those calls never touch the jvm. The jvm is only
  involved at the boundary, when clojure code calls a native procedure
  (or the other way around, which is not supported here).

  Untyped constant integers are treated as 64 bit integers.

  Pointers use the :ptr type. Memory is accessed with
  (load type pointer), (store type pointer value), (pointer-add pointer
  offset) and (to-pointer integer-address)."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is run-tests testing]])
  (:import (java.lang.foreign Arena FunctionDescriptor Linker MemorySegment
                              SymbolLookup ValueLayout)))

(declare compile-expression)

;;; ---------------------------------------------------------------------------
;;; Types
;;; ---------------------------------------------------------------------------

(defn llvm-type
  [type]
  (case type
    :i64 "i64"
    :i32 "i32"
    :f64 "double"
    :f32 "float"
    :ptr "ptr"
    (throw (ex-info (str "Unsupported clodin type: " type) {:type type}))))

(deftest test-llvm-type

  (is (= "i64" (llvm-type :i64)))
  (is (= "i32" (llvm-type :i32)))
  (is (= "double" (llvm-type :f64)))
  (is (= "float" (llvm-type :f32)))
  (is (= "ptr" (llvm-type :ptr)))
  (is (thrown? clojure.lang.ExceptionInfo (llvm-type :i8))))

;;; ---------------------------------------------------------------------------
;;; Module state
;;;

;;; procedure-registry maps a procedure name to
;;; {:param-types [...] :return-type ... :ir "..."}.
;;; procedure-handles maps a procedure name to a panama downcall
;;; MethodHandle from the most recently compiled library.
;;; ---------------------------------------------------------------------------

(def procedure-registry (atom {}))
(def procedure-handles (atom {}))
(defonce library-counter (atom 0))
(defonce library-arena (atom nil))

(def build-directory
  (delay (let [path (java.nio.file.Paths/get
                     (System/getProperty "user.dir")
                     (into-array String [".clodin"]))]
           (java.nio.file.Files/createDirectories path (make-array java.nio.file.attribute.FileAttribute 0))
           path)))

(defn module-ir
  "Concatenates the ir of every registered procedure into one llvm
  module. Function order does not matter in llvm, so calls to
  procedures defined earlier are resolved by the linker like any other
  module level symbol."
  []
  (string/join "\n\n" (map :ir (vals @procedure-registry))))

;;; ---------------------------------------------------------------------------
;;; Ir rendering
;;; ---------------------------------------------------------------------------

(defn render-procedure
  [name param-names param-types return-type result-value lines]
  (let [parameter-list (string/join
                        ", "
                        (map (fn [param-type param-name]
                               (str (llvm-type param-type) " %" param-name))
                             param-types
                             param-names))
        body (apply str (map (fn [line] (str "  " line "\n")) lines))
        return-line (if return-type
                      (str "  ret " (llvm-type return-type) " " result-value "\n")
                      "  ret void\n")]
    (str "define "
         (if return-type (llvm-type return-type) "void")
         " @\"" name "\"(" parameter-list ") {\n"
         "entry:\n"
         body
         return-line
         "}\n")))

(deftest test-render-procedure
  (is (= (str "define i64 @\"twice\"(i64 %number) {\n"
              "entry:\n"
              "  %1 = mul i64 %number, 2\n"
              "  ret i64 %1\n"
              "}\n")
         (render-procedure "twice" ["number"] [:i64] :i64 "%1"
                           ["%1 = mul i64 %number, 2"]))))

;;; ---------------------------------------------------------------------------
;;; Panama integration
;;; ---------------------------------------------------------------------------

(defn value-layout
  [type]
  (case type
    :i64 ValueLayout/JAVA_LONG
    :i32 ValueLayout/JAVA_INT
    :f64 ValueLayout/JAVA_DOUBLE
    :f32 ValueLayout/JAVA_FLOAT
    :ptr ValueLayout/ADDRESS
    (throw (ex-info (str "Unsupported clodin type: " type) {:type type}))))

(deftest test-value-layout
  (is (= ValueLayout/JAVA_LONG (value-layout :i64)))
  (is (= ValueLayout/JAVA_INT (value-layout :i32)))
  (is (= ValueLayout/JAVA_DOUBLE (value-layout :f64)))
  (is (= ValueLayout/JAVA_FLOAT (value-layout :f32)))
  (is (= ValueLayout/ADDRESS (value-layout :ptr)))
  (is (thrown? clojure.lang.ExceptionInfo (value-layout :i8))))

(defn- function-descriptor
  [return-type param-types]
  (let [argument-layouts (into-array java.lang.foreign.MemoryLayout
                                     (map value-layout param-types))]
    (if return-type
      (FunctionDescriptor/of (value-layout return-type) argument-layouts)
      (FunctionDescriptor/ofVoid argument-layouts))))

(defn- load-library!
  "Loads a freshly compiled shared library and rebuilds the downcall
  handles for every registered procedure. The previous library is
  unloaded by closing its arena, because the new library contains every
  procedure in the module."
  [library-path]
  (let [previous-arena @library-arena
        arena (Arena/ofShared)
        lookup (SymbolLookup/libraryLookup library-path arena)
        linker (Linker/nativeLinker)
        handles (into {}
                      (map (fn [[name {:keys [param-types return-type]}]]
                             [name (.downcallHandle
                                    linker
                                    (.findOrThrow lookup (str name))
                                    (function-descriptor return-type param-types)
                                    (make-array java.lang.foreign.Linker$Option 0))]))
                      @procedure-registry)]
    (reset! procedure-handles handles)
    (reset! library-arena arena)
    (when previous-arena
      (.close previous-arena))
    nil))

(defn- run-clang
  "Compiles an llvm ir text file into a shared library with clang."
  [ir-path library-path]
  (let [builder (doto (ProcessBuilder.
                       (into-array String
                                   ["clang" "-shared" "-O2"
                                    "-o" library-path ir-path]))
                  (.redirectErrorStream true))
        process (.start builder)
        output (slurp (.getInputStream process))
        exit-code (.waitFor process)]
    (when-not (zero? exit-code)
      (throw (ex-info "clang failed to compile clodin llvm ir"
                      {:exit-code exit-code
                       :output output
                       :ir-path ir-path})))))

(defn recompile-library!
  "Writes the whole module to a versioned .ll file, builds it into a
  shared library with clang, and loads it with panama. A new file name
  per compile avoids dlopen caching an old library under the same
  path."
  []
  (let [counter (swap! library-counter inc)
        ir-path (.resolve @build-directory (str "clodin-" counter ".ll"))
        library-path (.resolve @build-directory (str "clodin-" counter ".so"))]
    (spit (str ir-path) (module-ir))
    (run-clang (str ir-path) (str library-path))
    (load-library! (str library-path))
    nil))

(defn coerce-argument
  "Converts a clojure value into the java value that the panama
  downcall descriptor expects for the given clodin type."
  [type value]
  (case type
    :i64 (long value)
    :i32 (int value)
    :f64 (double value)
    :f32 (float value)
    :ptr (cond
           (instance? MemorySegment value) value
           (nil? value) (MemorySegment/ofAddress 0)
           (integer? value) (MemorySegment/ofAddress (long value))
           :else (throw (ex-info (str "Expected a MemorySegment or integer address for :ptr, got "
                                      (some-> value class .getName))
                                 {:type type :value value})))
    (throw (ex-info (str "Unsupported clodin type: " type) {:type type}))))

(defn invoke-procedure
  "Calls a native procedure through its panama downcall handle,
  coercing the clojure arguments to the declared types."
  [name arguments]
  (let [procedure (get @procedure-registry name)
        handle (get @procedure-handles name)]
    (when-not procedure
      (throw (ex-info (str "Unknown clodin procedure: " name) {:procedure name})))
    (when-not handle
      (throw (ex-info (str "No downcall handle for clodin procedure: " name)
                      {:procedure name})))
    (.invokeWithArguments
     handle
     (into-array Object
                 (map coerce-argument (:param-types procedure) arguments)))))

;;; ---------------------------------------------------------------------------
;;; Expression compilation
;;;
;;; An emitter state is {:lines [...] :temporary-index n}. Expression
;;; compilation returns [state value-representation type].
;;; ---------------------------------------------------------------------------

(defn- emit-line
  [state line]
  (update state :lines conj line))

(defn- fresh-temporary
  [state]
  (let [index (inc (:temporary-index state))]
    [(assoc state :temporary-index index) (str "%" index)]))

(defn- compile-operands
  [state environment operand-forms]
  (reduce (fn [[state values types] operand-form]
            (let [[state value type] (compile-expression state environment operand-form)]
              [state (conj values value) (conj types type)]))
          [state [] []]
          operand-forms))

(defn- arithmetic-instruction
  [type operator]
  (if (#{:f64 :f32} type)
    (case operator
      + "fadd"
      - "fsub"
      * "fmul"
      / "fdiv")
    (case operator
      + "add"
      - "sub"
      * "mul"
      / "sdiv")))

(defn- compile-arithmetic
  "Compiles a variadic arithmetic form by folding llvm binary
  operations left to right. All operands must have the same type."
  [state environment operator operand-forms]
  (when (< (count operand-forms) 2)
    (throw (ex-info (str "Arithmetic operator " operator " needs at least 2 operands")
                    {:operator operator})))
  (let [[state values types] (compile-operands state environment operand-forms)
        distinct-types (distinct types)]
    (when-not (= 1 (count distinct-types))
      (throw (ex-info (str "Mixed operand types " distinct-types
                           " for operator " operator)
                      {:operator operator :types types})))
    (let [type (first distinct-types)
          instruction (arithmetic-instruction type operator)]
      (loop [state state
             accumulated-value (first values)
             remaining-values (next values)]
        (if (nil? remaining-values)
          [state accumulated-value type]
          (let [[state result] (fresh-temporary state)
                state (emit-line state
                                 (format "%s = %s %s %s, %s"
                                         result
                                         instruction
                                         (llvm-type type)
                                         accumulated-value
                                         (first remaining-values)))]
            (recur state result (next remaining-values))))))))

(defn- compile-procedure-call
  "Compiles a call to another clodin procedure. The call is emitted as
  a direct llvm call inside the shared library, so the jvm is not
  involved when native procedures call each other."
  [state environment procedure-symbol argument-forms]
  (let [name (str procedure-symbol)
        procedure (get @procedure-registry (str procedure-symbol))]
    (when-not procedure
      (throw (ex-info (str "Unknown clodin procedure: " procedure-symbol)
                      {:procedure procedure-symbol})))
    (when-not (= (count argument-forms) (count (:param-types procedure)))
      (throw (ex-info (str "Procedure " name " expects "
                           (count (:param-types procedure))
                           " arguments, got " (count argument-forms))
                      {:procedure procedure-symbol})))
    (let [[state argument-values argument-types]
          (compile-operands state environment argument-forms)]
      (when-not (= argument-types (vec (:param-types procedure)))
        (throw (ex-info (str "Argument types " (vec argument-types)
                             " do not match parameters " (:param-types procedure)
                             " for procedure " name)
                        {:procedure procedure-symbol
                         :argument-types (vec argument-types)})))
      (let [argument-list (string/join
                           ", "
                           (map (fn [type value]
                                  (str (llvm-type type) " " value))
                                (:param-types procedure)
                                argument-values))]
        (if (:return-type procedure)
          (let [[state result] (fresh-temporary state)
                state (emit-line state
                                 (format "%s = call %s @\"%s\"(%s)"
                                         result
                                         (llvm-type (:return-type procedure))
                                         name
                                         argument-list))]
            [state result (:return-type procedure)])
          (let [state (emit-line state
                                 (format "call void @\"%s\"(%s)"
                                         name
                                         argument-list))]
            [state nil nil]))))))

(defn- compile-to-float
  "Compiles (to-float x), a cast of an integer value to a 64 bit float."
  [state environment expression]
  (let [[state value type] (compile-expression state environment (second expression))]
    (when-not (#{:i64 :i32} type)
      (throw (ex-info (str "to-float expects an integer, got " type)
                      {:type type})))
    (let [[state result] (fresh-temporary state)
          state (emit-line state
                           (format "%s = sitofp %s %s to double"
                                   result
                                   (llvm-type type)
                                   value))]
      [state result :f64])))

(defn- compile-load
  "Compiles (load type pointer) into an llvm load instruction."
  [state environment expression]
  (when-not (= 3 (count expression))
    (throw (ex-info "load expects (load type pointer)" {:expression expression})))
  (let [[element-type pointer-form] (rest expression)]
    (llvm-type element-type)
    (let [[state pointer-value pointer-type]
          (compile-expression state environment pointer-form)]
      (when-not (= :ptr pointer-type)
        (throw (ex-info (str "load expects a :ptr pointer, got " pointer-type)
                        {:type pointer-type})))
      (let [[state result] (fresh-temporary state)
            state (emit-line state
                             (format "%s = load %s, ptr %s"
                                     result
                                     (llvm-type element-type)
                                     pointer-value))]
        [state result element-type]))))

(defn- compile-store
  "Compiles (store type pointer value) into an llvm store instruction.
  A store does not produce a value."
  [state environment expression]
  (when-not (= 4 (count expression))
    (throw (ex-info "store expects (store type pointer value)"
                    {:expression expression})))
  (let [[element-type pointer-form value-form] (rest expression)]
    (llvm-type element-type)
    (let [[state pointer-value pointer-type]
          (compile-expression state environment pointer-form)
          [state stored-value stored-type]
          (compile-expression state environment value-form)]
      (when-not (= :ptr pointer-type)
        (throw (ex-info (str "store expects a :ptr pointer, got " pointer-type)
                        {:type pointer-type})))
      (when-not (= element-type stored-type)
        (throw (ex-info (str "store expects a " element-type " value, got " stored-type)
                        {:element-type element-type :value-type stored-type})))
      (let [state (emit-line state
                             (format "store %s %s, ptr %s"
                                     (llvm-type element-type)
                                     stored-value
                                     pointer-value))]
        [state nil nil]))))

(defn- compile-pointer-add
  "Compiles (pointer-add pointer offset) into an llvm getelementptr.
  The offset is measured in bytes."
  [state environment expression]
  (when-not (= 3 (count expression))
    (throw (ex-info "pointer-add expects (pointer-add pointer offset)"
                    {:expression expression})))
  (let [[pointer-form offset-form] (rest expression)
        [state pointer-value pointer-type]
        (compile-expression state environment pointer-form)
        [state offset-value offset-type]
        (compile-expression state environment offset-form)]
    (when-not (= :ptr pointer-type)
      (throw (ex-info (str "pointer-add expects a :ptr pointer, got " pointer-type)
                      {:type pointer-type})))
    (when-not (#{:i64 :i32} offset-type)
      (throw (ex-info (str "pointer-add expects an integer offset, got " offset-type)
                      {:type offset-type})))
    (let [[state result] (fresh-temporary state)
          state (emit-line state
                           (format "%s = getelementptr inbounds i8, ptr %s, %s %s"
                                   result
                                   pointer-value
                                   (llvm-type offset-type)
                                   offset-value))]
      [state result :ptr])))

(defn- compile-to-pointer
  "Compiles (to-pointer address), converting an integer address to :ptr."
  [state environment expression]
  (when-not (= 2 (count expression))
    (throw (ex-info "to-pointer expects (to-pointer address)"
                    {:expression expression})))
  (let [[state value type]
        (compile-expression state environment (second expression))]
    (when-not (#{:i64 :i32} type)
      (throw (ex-info (str "to-pointer expects an integer address, got " type)
                      {:type type})))
    (let [[state result] (fresh-temporary state)
          state (emit-line state
                           (format "%s = inttoptr %s %s to ptr"
                                   result
                                   (llvm-type type)
                                   value))]
      [state result :ptr])))

(defn- compile-expression
  [state environment expression]
  (cond
    (integer? expression) [state (str expression) :i64]
    (float? expression) [state (str expression) :f64]
    (symbol? expression) (let [type (get environment expression)]
                           (when-not type
                             (throw (ex-info (str "Unknown symbol: " expression)
                                             {:symbol expression})))
                           [state (str "%" expression) type])
    (and (seq? expression) (#{'+ '- '* '/} (first expression)))
    (compile-arithmetic state environment (first expression) (rest expression))
    (and (seq? expression) (= 'to-float (first expression)))
    (compile-to-float state environment expression)
    (and (seq? expression) (= 'load (first expression)))
    (compile-load state environment expression)
    (and (seq? expression) (= 'store (first expression)))
    (compile-store state environment expression)
    (and (seq? expression) (= 'pointer-add (first expression)))
    (compile-pointer-add state environment expression)
    (and (seq? expression) (= 'to-pointer (first expression)))
    (compile-to-pointer state environment expression)
    (seq? expression)
    (compile-procedure-call state environment (first expression) (rest expression))
    :else (throw (ex-info (str "Unsupported clodin expression: " expression)
                          {:expression expression}))))

(defn- compile-body
  "Compiles the body expressions in order and returns the emitter
  state extended with the result value and return type of the last
  expression."
  [state environment body-forms]
  (reduce (fn [state body-form]
            (let [[state value type] (compile-expression state environment body-form)]
              (assoc state :result-value value :return-type type)))
          (assoc state :result-value nil :return-type nil)
          body-forms))

;;; ---------------------------------------------------------------------------
;;; Compilation entry points
;;; ---------------------------------------------------------------------------

(defn compile-procedure!
  "Registers the ir of a procedure in the module and recompiles and
  reloads the whole library so that the new procedure and all updated
  callers are available."
  [name param-names param-types return-type result-value lines]
  (let [ir (render-procedure name param-names param-types return-type result-value lines)]
    (swap! procedure-registry
           assoc name
           {:param-types (vec param-types)
            :return-type return-type
            :ir ir})
    (recompile-library!)))

(defmacro defproc
  "Defines a native clodin procedure.

  (defproc twice [number :i64] (* number 2))

  At macroexpansion time the body is type checked (untyped constant
  integers are 64 bit integers), rendered as llvm ir, the whole
  accumulated module is compiled into a shared library with clang and
  loaded with panama. Expands to a clojure function of the same name
  that downcalls into the native procedure.

  An explicit return type can be given by putting a type keyword
  before the body forms; without it the return type is inferred from
  the last body expression.

  Pointers are declared as :ptr. Memory can be read and written with
  (load :i64 pointer) and (store :i64 pointer value)."
  [name params & body]
  {:pre [(symbol? name) (vector? params) (even? (count params))]}
  (let [param-pairs (partition 2 params)
        param-names (mapv first param-pairs)
        param-types (mapv second param-pairs)
        explicit-return-type (when (keyword? (first body)) (first body))
        body-forms (if explicit-return-type (next body) body)
        {:keys [result-value return-type lines]}
        (compile-body {:lines [] :temporary-index 0}
                      (zipmap param-names param-types)
                      body-forms)
        return-type (or explicit-return-type return-type)]
    (when (and return-type (nil? result-value))
      (throw (ex-info (str "Procedure " name " declares return type " return-type
                           " but its body does not produce a value")
                      {:procedure name :return-type return-type})))
    (compile-procedure! (str name) param-names param-types return-type result-value lines)
    `(defn ~name [~@param-names]
       (invoke-procedure ~(str name) [~@param-names]))))

;;; ---------------------------------------------------------------------------
;;; Procedures and tests
;;; ---------------------------------------------------------------------------

(defproc substract-one [number :i64]
  (- number 1))

(defproc add-some [number :i64]
  (+ number 42))

(defproc add-both [a :i64 b :i64]
  (+ a b))

(defproc add-gigabyte [number :i64]
  (+ number 1073741824))

(defproc halve [number :f64]
  (* number 0.5))

(defproc mix-both [a :i64 b :f64]
  (+ b (halve (to-float (add-both a 1)))))

(defproc store-i64-at [pointer :ptr value :i64]
  (store :i64 pointer value))

(defproc load-i64-at [pointer :ptr]
  (load :i64 pointer))

(defproc store-i64-through-address [address :i64 value :i64]
  (store :i64 (to-pointer address) value))

(defproc store-second-i64 [pointer :ptr value :i64]
  (store :i64 (pointer-add pointer 8) value))

(defproc increment-i64-at [pointer :ptr]
  (store-i64-at pointer (+ (load :i64 pointer) 1)))

(defproc pointer-to-second-i64 [pointer :ptr]
  (pointer-add pointer 8))

(deftest test-substract-one
  (is (= 9 (substract-one 10))))

(deftest test-add-some
  (is (= 43 (add-some 1))))

(deftest test-add-both
  (is (= 7 (add-both 3 4)))
  (is (= -2 (add-both 2 -4))))

(deftest test-add-gigabyte
  (testing "untyped constant integers are 64 bit integers"
    (is (= 1073741825 (add-gigabyte 1)))))

(deftest test-halve
  (is (= 2.5 (halve 5.0))))

(deftest test-mix-both
  (testing "native procedures call each other and float calls flow through"
    (is (< (abs (- 6.0 (mix-both 3 4.0))) 0.000001))))

(deftest test-pointer-memory
  (let [arena (Arena/ofShared)
        buffer (.allocate arena 16)]
    (try
      (store-i64-at buffer 42)
      (is (= 42 (load-i64-at buffer)))
      (is (= 42 (.getAtIndex buffer ValueLayout/JAVA_LONG 0)))
      (store-second-i64 buffer 99)
      (is (= 99 (.getAtIndex buffer ValueLayout/JAVA_LONG 1)))
      (store-i64-through-address (.address buffer) 7)
      (is (= 7 (load-i64-at buffer)))
      (increment-i64-at buffer)
      (is (= 8 (load-i64-at buffer)))
      (store-i64-at (pointer-to-second-i64 buffer) 123)
      (is (= 123 (load-i64-at (pointer-to-second-i64 buffer))))
      (finally
        (.close arena)))))

(deftest test-coerce-pointer-argument
  (let [arena (Arena/ofShared)
        buffer (.allocate arena 8)]
    (try
      (is (identical? buffer (coerce-argument :ptr buffer)))
      (is (instance? MemorySegment (coerce-argument :ptr (.address buffer))))
      (finally
        (.close arena)))))

(defn -main
  [& _]
  (let [summary (run-tests 'clodin.compiler)]
    (when (or (pos? (:fail summary 0)) (pos? (:error summary 0)))
      (System/exit 1))))
