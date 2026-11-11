(ns clodin.compiler
  "A low level odin-like language hosted in clojure.

  Clodin source code is written as clojure data structures. The
  defproc macro compiles a procedure into llvm ir and registers it
  in the accumulated module. The module is compiled into a native
  shared library with clang and loaded with project panama lazily,
  when a clojure wrapper first calls a native procedure. The wrapper
  has the same name as the procedure and downcalls into it.

  Native procedures call each other with direct llvm calls inside the
  shared library, so those calls never touch the jvm. The jvm is only
  involved at the boundary, when clojure code calls a native procedure
  (or the other way around, which is not supported here).

  Untyped constant integers are treated as 64 bit integers.

  Pointers use the :ptr type. Memory is accessed with
  (load type pointer), (store type pointer value), (pointer-add pointer
  offset) and (to-pointer integer-address).

  Local variables are introduced with (let [name type value ...] body...)
  and mutated with (set name value). Loops are written with
  (while condition body...)."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is run-tests testing]])
  (:import (java.lang.foreign Arena FunctionDescriptor Linker MemorySegment
                              SymbolLookup ValueLayout)))

(declare compile-expression compile-body)

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
;;; module-dirty is true when a procedure has been registered after
;;; the last successful library compile.
;;; procedure-handles maps a procedure name to a panama downcall
;;; MethodHandle from the most recently compiled library.
;;; ---------------------------------------------------------------------------

(def procedure-registry (atom {}))
(def procedure-handles (atom {}))
(def module-dirty (atom true))
(defonce library-counter (atom 0))
(defonce library-arena (atom nil))
(defonce compile-lock (Object.))

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
        body (apply str (map (fn [line]
                               (if (string/ends-with? line ":")
                                 (str line "\n")
                                 (str "  " line "\n")))
                             lines))
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
                           ["%1 = mul i64 %number, 2"])))
  (testing "labels are rendered without indentation"
    (is (= (str "define void @\"loop\"() {\n"
                "entry:\n"
                "  br label %while.cond\n"
                "while.cond:\n"
                "  br label %while.end\n"
                "while.end:\n"
                "  ret void\n"
                "}\n")
           (render-procedure "loop" [] [] nil nil
                             ["br label %while.cond"
                              "while.cond:"
                              "br label %while.end"
                              "while.end:"])))))

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

(defn compile-module!
  "Compiles and loads the accumulated module if a procedure
  registration marked it dirty. This batches all defproc forms that
  were evaluated since the last compile into one clang invocation."
  []
  (locking compile-lock
    (when @module-dirty
      (recompile-library!)
      (reset! module-dirty false))
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
  coercing the clojure arguments to the declared types. If procedures
  have been registered since the last compile, the module is compiled
  and loaded first."
  [name arguments]
  (let [procedure (get @procedure-registry name)]
    (when-not procedure
      (throw (ex-info (str "Unknown clodin procedure: " name) {:procedure name})))
    (compile-module!)
    (let [handle (get @procedure-handles name)]
      (when-not handle
        (throw (ex-info (str "No downcall handle for clodin procedure: " name)
                        {:procedure name})))
      (.invokeWithArguments
       handle
       (into-array Object
                   (map coerce-argument (:param-types procedure) arguments))))))

;;; ---------------------------------------------------------------------------
;;; Expression compilation
;;;
;;; An emitter state is
;;; {:lines [...] :temporary-index n :label-index n :block-terminated? bool}.
;;; Expression compilation returns [state value-representation type].
;;; A form that produces no value, such as store, set or while, returns
;;; nil as both the value and the type.
;;;
;;; The environment maps a local name to {:type ... :slot "%name"}.
;;; Locals live in stack slots allocated with alloca, so a set inside a
;;; loop is visible in the next iteration without building phi nodes.
;;; ---------------------------------------------------------------------------

(defn- emit-line
  [state line]
  (update state :lines conj line))

(defn- emit-label
  "Emits a basic block label. Labels are not indented."
  [state label]
  (update state :lines conj (str label ":")))

(defn- terminate-block
  "Marks the current basic block as terminated, so that no further
  instructions are emitted into it."
  [state]
  (assoc state :block-terminated? true))

(defn- fresh-temporary
  [state]
  (let [index (inc (:temporary-index state))]
    [(assoc state :temporary-index index) (str "%" index)]))

(defn- fresh-label
  [state prefix]
  (let [index (inc (:label-index state))]
    [(assoc state :label-index index) (str prefix "." index)]))

(defn- fresh-local-slot
  "Returns a stack slot name that is unique per binding. A fresh slot per
  binding lets a later let binding shadow an earlier one of the same
  name without overwriting its storage."
  [state name]
  (let [index (inc (:slot-index state))]
    [(assoc state :slot-index index) (str "%" name ".slot." index)]))

(defn- compile-local-reference
  "Compiles a reference to a local. Parameters are already llvm values,
  while let bindings live in stack slots and are loaded."
  [state environment name]
  (let [local (get environment name)]
    (when-not local
      (throw (ex-info (str "Unknown symbol: " name) {:symbol name})))
    (if (:parameter? local)
      [state (:slot local) (:type local)]
      (let [[state result] (fresh-temporary state)
            state (emit-line state
                             (format "%s = load %s, ptr %s"
                                     result
                                     (llvm-type (:type local))
                                     (:slot local)))]
        [state result (:type local)]))))

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
        [(assoc state :result-value result :return-type element-type)
         result
         element-type]))))

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
      [(assoc state :result-value result :return-type :ptr) result :ptr])))

(defn binding-names
  "Returns the names bound by a let form, or nil for other forms."
  [form]
  (when (and (seq? form)
             (= 'let (first form))
             (vector? (second form)))
    (set (take-nth 3 (second form)))))

(deftest test-binding-names
  (is (= #{'x} (binding-names '(let [x :i64 1] x))))
  (is (= #{'x 'y} (binding-names '(let [x :i64 1 y :i64 2] x))))
  (is (nil? (binding-names '(+ 1 2)))))

(defn- compile-let
  "Compiles (let [name type value ...] body...). Every binding gets its
  own stack slot, so a later binding may shadow an earlier one and the
  initial value of a binding is evaluated in the environment of the
  bindings before it."
  [state environment expression]
  (let [bindings (second expression)
        body-forms (drop 2 expression)]
    (when-not (vector? bindings)
      (throw (ex-info "let expects a vector of bindings" {:expression expression})))
    (when-not (zero? (mod (count bindings) 3))
      (throw (ex-info "let bindings must come in name, type, value triples"
                      {:bindings bindings})))
    (when (empty? body-forms)
      (throw (ex-info "let expects at least one body form" {:expression expression})))
    (let [binding-pairs (partition 3 bindings)
          [state environment]
          (reduce (fn [[state environment] [name type value-form]]
                    (when-not (symbol? name)
                      (throw (ex-info (str "let binding name must be a symbol, got " name)
                                      {:name name})))
                    (llvm-type type)
                    (let [[state value value-type]
                          (compile-expression state environment value-form)]
                      (when-not (= type value-type)
                        (throw (ex-info (str "let binding " name " is declared as " type
                                             " but its value is " value-type)
                                        {:name name :type type :value-type value-type})))
                      (let [[state slot] (fresh-local-slot state name)
                            state (emit-line state
                                             (format "%s = alloca %s"
                                                     slot
                                                     (llvm-type type)))
                            state (emit-line state
                                             (format "store %s %s, ptr %s"
                                                     (llvm-type type)
                                                     value
                                                     slot))]
                        [state (assoc environment name {:type type :slot slot})])))
                  [state environment]
                  binding-pairs)]
      (let [inner (compile-body state environment body-forms)]
        [inner (:result-value inner) (:return-type inner)]))))

(defn- compile-set
  "Compiles (set name value), storing the value into the stack slot of
  the local. A set does not produce a value."
  [state environment expression]
  (when-not (= 3 (count expression))
    (throw (ex-info "set expects (set name value)" {:expression expression})))
  (let [[name value-form] (rest expression)
        local (get environment name)]
    (when-not (symbol? name)
      (throw (ex-info (str "set expects a local name, got " name) {:name name})))
    (when-not local
      (throw (ex-info (str "Cannot set unknown local: " name) {:symbol name})))
    (let [[state value value-type] (compile-expression state environment value-form)]
      (when-not (= (:type local) value-type)
        (throw (ex-info (str "Cannot set " name " of type " (:type local)
                             " to a value of type " value-type)
                        {:name name :type (:type local) :value-type value-type})))
      (let [state (emit-line state
                             (format "store %s %s, ptr %s"
                                     (llvm-type value-type)
                                     value
                                     (:slot local)))]
        [state nil nil]))))

(defn- comparison-instruction
  [type operator]
  (if (#{:f64 :f32} type)
    (case operator
      < "fcmp olt"
      <= "fcmp ole"
      > "fcmp ogt"
      >= "fcmp oge"
      = "fcmp oeq"
      not= "fcmp une")
    (case operator
      < "icmp slt"
      <= "icmp sle"
      > "icmp sgt"
      >= "icmp sge"
      = "icmp eq"
      not= "icmp ne")))

(deftest test-comparison-instruction
  (is (= "icmp slt" (comparison-instruction :i64 '<)))
  (is (= "icmp ne" (comparison-instruction :i32 'not=)))
  (is (= "fcmp oge" (comparison-instruction :f64 '>=)))
  (is (= "fcmp une" (comparison-instruction :f32 'not=))))

(defn- compile-comparison
  "Compiles a binary comparison into an llvm icmp or fcmp instruction.
  The result is an i1."
  [state environment operator operand-forms]
  (when-not (= 2 (count operand-forms))
    (throw (ex-info (str "Comparison " operator " needs exactly 2 operands")
                    {:operator operator})))
  (let [[state values types] (compile-operands state environment operand-forms)
        distinct-types (distinct types)]
    (when-not (= 1 (count distinct-types))
      (throw (ex-info (str "Mixed operand types " distinct-types
                           " for comparison " operator)
                      {:operator operator :types types})))
    (let [type (first distinct-types)
          [state result] (fresh-temporary state)
          state (emit-line state
                           (format "%s = %s %s %s, %s"
                                   result
                                   (comparison-instruction type operator)
                                   (llvm-type type)
                                   (first values)
                                   (second values)))]
      [state result :i1])))

(defn- compile-while
  "Compiles (while condition body...) into a condition block, a body
  block and an end block. The condition must be an i1."
  [state environment expression]
  (let [condition-form (second expression)
        body-forms (drop 2 expression)]
    (when-not condition-form
      (throw (ex-info "while expects a condition" {:expression expression})))
    (let [[state condition-label] (fresh-label state "while.cond")
          [state body-label] (fresh-label state "while.body")
          [state end-label] (fresh-label state "while.end")
          state (emit-line state (str "br label %" condition-label))
          state (emit-label state condition-label)
          [state condition-value condition-type]
          (compile-expression state environment condition-form)]
      (when-not (= :i1 condition-type)
        (throw (ex-info (str "while condition must be a comparison, got " condition-type)
                        {:type condition-type})))
      (let [state (emit-line state
                             (format "br i1 %s, label %%%s, label %%%s"
                                     condition-value
                                     body-label
                                     end-label))
            state (terminate-block state)
            state (emit-label state body-label)
            state (compile-body state environment body-forms)
            state (emit-line state (str "br label %" condition-label))
            state (terminate-block state)
            state (emit-label state end-label)]
        [(assoc state :result-value nil :return-type nil) nil nil]))))

(defn- compile-expression
  [state environment expression]
  (cond
    (integer? expression) [state (str expression) :i64]
    (float? expression) [state (str expression) :f64]
    (symbol? expression) (compile-local-reference state environment expression)
    (and (seq? expression) (#{'+ '- '* '/} (first expression)))
    (compile-arithmetic state environment (first expression) (rest expression))
    (and (seq? expression) (#{'< '<= '> '>= '= 'not=} (first expression)))
    (compile-comparison state environment (first expression) (rest expression))
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
    (and (seq? expression) (= 'let (first expression)))
    (compile-let state environment expression)
    (and (seq? expression) (= 'set (first expression)))
    (compile-set state environment expression)
    (and (seq? expression) (= 'while (first expression)))
    (compile-while state environment expression)
    (seq? expression)
    (compile-procedure-call state environment (first expression) (rest expression))
    :else (throw (ex-info (str "Unsupported clodin expression: " expression)
                          {:expression expression}))))

(defn- compile-body
  "Compiles the body expressions in order and returns the emitter
  state extended with the result value and return type of the last
  expression. A body form that produces no value, such as set or
  while, leaves the result value nil."
  [state environment body-forms]
  (reduce (fn [state body-form]
            (let [[state value type] (compile-expression state environment body-form)]
              (assoc state
                     :result-value value
                     :return-type type)))
          (assoc state :result-value nil :return-type nil)
          body-forms))

;;; ---------------------------------------------------------------------------
;;; Compilation entry points
;;; ---------------------------------------------------------------------------

(defn- register-procedure!
  "Registers the ir and signature of a procedure and marks the module
  dirty. The module is compiled later, once, when a native procedure
  is first called."
  [name param-names param-types return-type result-value lines]
  (let [ir (render-procedure name param-names param-types return-type result-value lines)]
    (swap! procedure-registry
           assoc name
           {:param-types (vec param-types)
            :return-type return-type
            :ir ir})
    (reset! module-dirty true)
    nil))

(defn compile-procedure!
  "Registers a procedure and immediately compiles and loads the whole
  module. defproc uses register-procedure! instead so that a batch of
  procedures is compiled only once."
  [name param-names param-types return-type result-value lines]
  (register-procedure! name param-names param-types return-type result-value lines)
  (compile-module!))

(defn- defproc-form
  "Compiles the procedure described by a defproc form and returns the
  clojure defn form that calls the native procedure."
  [name params body]
  (when-not (symbol? name)
    (throw (ex-info "Procedure name must be a symbol" {:name name})))
  (when-not (vector? params)
    (throw (ex-info "Procedure parameters must be a vector" {:params params})))
  (when-not (even? (count params))
    (throw (ex-info "Procedure parameters must come in name, type pairs"
                    {:params params})))
  (let [param-pairs (partition 2 params)
        param-names (mapv first param-pairs)
        param-types (mapv second param-pairs)
        explicit-return-type (when (keyword? (first body)) (first body))
        body-forms (if explicit-return-type (next body) body)
        {:keys [result-value return-type lines]}
        (compile-body {:lines [] :temporary-index 0 :label-index 0 :slot-index 0}
                      (into {} (map (fn [param-name param-type]
                                      [param-name {:type param-type
                                                   :slot (str "%" param-name)
                                                   :parameter? true}])
                                    param-names
                                    param-types))
                      body-forms)
        return-type (or explicit-return-type return-type)]
    (when (and return-type (nil? result-value))
      (throw (ex-info (str "Procedure " name " declares return type " return-type
                           " but its body does not produce a value")
                      {:procedure name :return-type return-type})))
    (register-procedure! (str name) param-names param-types return-type result-value lines)
    `(defn ~name [~@param-names]
       (invoke-procedure ~(str name) [~@param-names]))))

(defmacro defproc
  "Defines a native clodin procedure.

  (defproc twice [number :i64] (* number 2))

  At macroexpansion time the body is type checked (untyped constant
  integers are 64 bit integers) and rendered as llvm ir. The
  procedure is registered in the accumulated module, but clang is not
  invoked yet. The whole module is compiled and loaded lazily on the
  first call to any native procedure wrapper.

  An explicit return type can be given by putting a type keyword
  before the body forms; without it the return type is inferred from
  the last body expression.

  Pointers are declared as :ptr. Memory can be read and written with
  (load :i64 pointer) and (store :i64 pointer value).

  Locals are introduced with (let [name type value ...] body...) and
  mutated with (set name value). Loops are written with
  (while condition body...)."
  [name params & body]
  (defproc-form name params body))

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

(defproc shadowed-let []
  (let [x :i64 10
        x :i64 (+ x 10)]
    x))

(defproc sum-to [limit :i64]
  (let [sum :i64 0
        index :i64 0]
    (while (< index limit)
      (set sum (+ sum index))
      (set index (+ index 1)))
    sum))

(defproc count-down [start :i64]
  (let [value :i64 start
        steps :i64 0]
    (while (> value 0)
      (set value (- value 1))
      (set steps (+ steps 1)))
    steps))

(defproc sum-floats-while [limit :f64]
  (let [sum :f64 0.0
        value :f64 0.0]
    (while (< value limit)
      (set sum (+ sum value))
      (set value (+ value 1.0)))
    sum))

(defproc fill-i64 [pointer :ptr value :i64 count :i64]
  (let [index :i64 0]
    (while (< index count)
      (store :i64 (pointer-add pointer (* index 8)) value)
      (set index (+ index 1)))))

(defproc sum-array-i64 [pointer :ptr count :i64]
  (let [sum :i64 0
        index :i64 0]
    (while (< index count)
      (set sum (+ sum (load :i64 (pointer-add pointer (* index 8)))))
      (set index (+ index 1)))
    sum))

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

(deftest test-shadowed-let
  (testing "a later let binding shadows an earlier one"
    (is (= 20 (shadowed-let)))))

(deftest test-while
  (is (= 10 (sum-to 5)))
  (is (= 0 (sum-to 0)))
  (is (= 5 (count-down 5)))
  (is (= 6.0 (sum-floats-while 4.0))))

(deftest test-while-with-memory
  (let [arena (Arena/ofShared)
        buffer (.allocate arena 32)]
    (try
      (fill-i64 buffer 3 4)
      (is (= 12 (sum-array-i64 buffer 4)))
      (is (= 3 (.getAtIndex buffer ValueLayout/JAVA_LONG 3)))
      (finally
        (.close arena)))))

(defn -main
  [& _]
  (let [summary (run-tests 'clodin.compiler)]
    (when (or (pos? (:fail summary 0)) (pos? (:error summary 0)))
      (System/exit 1))))
