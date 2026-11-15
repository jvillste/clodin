(ns clodin.compiler
  "A low level odin-like language hosted in clojure.

  Clodin source code is written as clojure data structures. The defproc
  macro type-checks a procedure, generates its LLVM IR through the LLVM C
  API (clodin.llvm-api) and serialises the finished module to in-memory
  bitcode. The bitcode is stored in the registry. When a procedure is
  first called the whole module (all registered procedures, linked from
  their stored bitcode) is compiled in-process with LLVM's ORC/LLJIT JIT
  and the wrappers downcall into it through panama. Nothing is written to
  disk and no clang process is involved.

  Native procedures call each other with direct llvm calls inside the JIT
  module, so those calls never touch the jvm. The jvm is only involved at
  the boundary, when clojure code calls a native procedure.

  Untyped constant integers are treated as 64 bit integers.

  Pointers use the :ptr type. Memory is accessed with
  (load type pointer), (store type pointer value), (pointer-add pointer
  offset) and (to-pointer integer-address).

  Local variables are introduced with (let [name type value ...] body...)
  and mutated with (set name value). Loops are written with
  (while condition body...)."
  (:require [clodin.llvm-api :as llvm]
            [clojure.test :refer [deftest is run-tests testing]])
  (:import (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemorySegment ValueLayout)))

(declare compile-expression compile-body)

;;; ---------------------------------------------------------------------------
;;; Module state
;;;
;;; procedure-registry maps a procedure name to
;;; {:param-types [...] :return-type ... :bitcode byte[] :callees {...}}.
;;; The bitcode is produced once, at defproc evaluation, and reused on
;;; every whole-module rebuild.
;;; procedure-handles maps a procedure name to a panama downcall
;;; MethodHandle into the most recent JIT generation.
;;; module-dirty is true when a procedure has been registered after the
;;; last successful JIT compile.
;;; ---------------------------------------------------------------------------

(def procedure-registry (atom {}))
(def procedure-handles (atom {}))
(def module-dirty (atom true))
(defonce current-jit (atom nil))
(defonce compile-lock (Object.))

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

(defn coerce-argument
  "Converts a clojure value into the java value that the panama downcall
  descriptor expects for the given clodin type."
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

;;; ---------------------------------------------------------------------------
;;; Expression compilation
;;;
;;; The emitter state is
;;; {:arena ... :context ... :module ... :builder ... :function ...
;;;  :entry-block ... :current-block ... :callees {...}}.
;;; The LLVM handles are opaque MemorySegments. Expression compilation
;;; returns [state value type] where value is an LLVMValueRef (or nil for
;;; a form that produces no value such as store, set or while).
;;;
;;; The environment maps a local name to {:type ... :value ... :kind ...}.
;;; Parameters are raw LLVM values (:kind :param); let bindings live in
;;; stack slots (:kind :local) so a set inside a loop is visible in the
;;; next iteration without building phi nodes. Local allocas are hoisted
;;; to the front of the entry block, so a let inside a loop does not grow
;;; the stack on every iteration.
;;; ---------------------------------------------------------------------------

(defn- compile-operands
  [state environment operand-forms]
  (reduce (fn [[state values types] operand-form]
            (let [[state value type] (compile-expression state environment operand-form)]
              [state (conj values value) (conj types type)]))
          [state [] []]
          operand-forms))

(defn- compile-local-reference
  [state environment name]
  (let [local (get environment name)]
    (when-not local
      (throw (ex-info (str "Unknown symbol: " name) {:symbol name})))
    (if (= :param (:kind local))
      [state (:value local) (:type local)]
      (let [value (llvm/build-load (:builder state)
                                   (llvm/type-for (:context state) (:type local))
                                   (:value local) (:arena state) nil)]
        [state value (:type local)]))))

(defn- float-type?
  [type]
  (#{:f64 :f32} type))

(defn- compile-arithmetic
  "Compiles a variadic arithmetic form by folding llvm binary operations
  left to right. All operands must have the same type."
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
          builder (:builder state)
          arena (:arena state)
          step (fn [accumulate value]
                 (if (float-type? type)
                   (case operator
                     + (llvm/build-float-add builder accumulate value nil arena)
                     - (llvm/build-float-sub builder accumulate value nil arena)
                     * (llvm/build-float-mul builder accumulate value nil arena)
                     / (llvm/build-float-div builder accumulate value nil arena))
                   (case operator
                     + (llvm/build-int-add builder accumulate value nil arena)
                     - (llvm/build-int-sub builder accumulate value nil arena)
                     * (llvm/build-int-mul builder accumulate value nil arena)
                     / (llvm/build-int-sdiv builder accumulate value nil arena))))
          result (reduce step (first values) (rest values))]
      [state result type])))

(defn- compile-procedure-call
  "Compiles a call to another clodin procedure. The call is emitted as a
  direct llvm call inside the JIT module, so the jvm is not involved when
  native procedures call each other."
  [state environment procedure-symbol argument-forms]
  (let [name (str procedure-symbol)
        procedure (get @procedure-registry name)]
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
      (let [{:keys [module context builder arena]} state
            name-token (llvm/c-string arena name)
            callee-type (llvm/function-type context arena (:return-type procedure)
                                            (:param-types procedure))
            callee (llvm/get-or-add-function module name-token callee-type)
            result (llvm/build-call builder callee-type callee argument-values arena)
            state (assoc state :callees
                         (assoc (:callees state) name
                                {:param-types (vec (:param-types procedure))
                                 :return-type (:return-type procedure)}))]
        (if (:return-type procedure)
          [state result (:return-type procedure)]
          [state nil nil])))))

(defn- compile-to-float
  "Compiles (to-float x), a cast of an integer value to a 64 bit float."
  [state environment expression]
  (let [[state value type] (compile-expression state environment (second expression))]
    (when-not (#{:i64 :i32} type)
      (throw (ex-info (str "to-float expects an integer, got " type)
                      {:type type})))
    (let [result (llvm/build-sitofp (:builder state) value
                                    (llvm/type-for (:context state) :f64)
                                    (:arena state) nil)]
      [state result :f64])))

(defn- compile-load
  "Compiles (load type pointer) into an llvm load instruction."
  [state environment expression]
  (when-not (= 3 (count expression))
    (throw (ex-info "load expects (load type pointer)" {:expression expression})))
  (let [[element-type pointer-form] (rest expression)
        [state pointer-value pointer-type]
        (compile-expression state environment pointer-form)]
    (when-not (= :ptr pointer-type)
      (throw (ex-info (str "load expects a :ptr pointer, got " pointer-type)
                      {:type pointer-type})))
    (let [result (llvm/build-load (:builder state)
                                  (llvm/type-for (:context state) element-type)
                                  pointer-value (:arena state) nil)]
      [state result element-type])))

(defn- compile-store
  "Compiles (store type pointer value) into an llvm store instruction.
  A store does not produce a value."
  [state environment expression]
  (when-not (= 4 (count expression))
    (throw (ex-info "store expects (store type pointer value)"
                    {:expression expression})))
  (let [[element-type pointer-form value-form] (rest expression)
        [state pointer-value pointer-type]
        (compile-expression state environment pointer-form)
        [state stored-value stored-type]
        (compile-expression state environment value-form)]
    (when-not (= :ptr pointer-type)
      (throw (ex-info (str "store expects a :ptr pointer, got " pointer-type)
                      {:type pointer-type})))
    (when-not (= element-type stored-type)
      (throw (ex-info (str "store expects a " element-type " value, got " stored-type)
                      {:element-type element-type :value-type stored-type})))
    (llvm/build-store (:builder state) stored-value pointer-value)
    [state nil nil]))

(defn- compile-pointer-add
  "Compiles (pointer-add pointer offset) into a getelementptr. The offset
  is measured in bytes."
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
    (let [result (llvm/build-bytes-gep state pointer-value offset-value)]
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
    (let [result (llvm/build-inttoptr (:builder state) value
                                      (llvm/type-for (:context state) :ptr)
                                      (:arena state) nil)]
      [state result :ptr])))

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
    (let [[state environment]
          (reduce (fn [[state environment] [name type value-form]]
                    (when-not (symbol? name)
                      (throw (ex-info (str "let binding name must be a symbol, got " name)
                                      {:name name})))
                    (llvm/type-for (:context state) type)
                    (let [[state value value-type]
                          (compile-expression state environment value-form)]
                      (when-not (= type value-type)
                        (throw (ex-info (str "let binding " name " is declared as " type
                                             " but its value is " value-type)
                                        {:name name :type type :value-type value-type})))
                      (let [alloca (llvm/hoisted-alloca
                                    (:builder state) (:context state) (:arena state)
                                    (:entry-block state) (:current-block state)
                                    (llvm/type-for (:context state) type))
                            _ (llvm/build-store (:builder state) value alloca)]
                        [state (assoc environment name
                                      {:type type :value alloca :kind :local})])))
                  [state environment]
                  (partition 3 bindings))]
      (let [inner (compile-body state environment body-forms)]
        [inner (:result-value inner) (:return-type inner)]))))

(defn- compile-set
  "Compiles (set name value), storing the value into the stack slot of the
  local. A set does not produce a value. Parameters are raw values and
  cannot be set."
  [state environment expression]
  (when-not (= 3 (count expression))
    (throw (ex-info "set expects (set name value)" {:expression expression})))
  (let [[name value-form] (rest expression)
        local (get environment name)]
    (when-not (symbol? name)
      (throw (ex-info (str "set expects a local name, got " name) {:name name})))
    (when-not local
      (throw (ex-info (str "Cannot set unknown local: " name) {:symbol name})))
    (when (= :param (:kind local))
      (throw (ex-info (str "Cannot set the function parameter " name
                           "; parameters are raw llvm values, not stack slots")
                      {:name name})))
    (let [[state value value-type] (compile-expression state environment value-form)]
      (when-not (= (:type local) value-type)
        (throw (ex-info (str "Cannot set " name " of type " (:type local)
                             " to a value of type " value-type)
                        {:name name :type (:type local) :value-type value-type})))
      (llvm/build-store (:builder state) value (:value local))
      [state nil nil])))

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
          builder (:builder state)
          arena (:arena state)
          result (if (float-type? type)
                   (llvm/build-fcmp builder operator (first values) (second values) arena)
                   (llvm/build-icmp builder operator (first values) (second values) arena))]
      [state result :i1])))

(defn- compile-while
  "Compiles (while condition body...) into a condition block, a body block
  and an end block. The condition must be an i1."
  [state environment expression]
  (let [condition-form (second expression)
        body-forms (drop 2 expression)]
    (when-not condition-form
      (throw (ex-info "while expects a condition" {:expression expression})))
    (let [{:keys [context function builder arena] :as state} state
          condition-block (llvm/new-block context arena "while.cond")
          body-block (llvm/new-block context arena "while.body")
          end-block (llvm/new-block context arena "while.end")]
      (llvm/append-block function condition-block)
      (llvm/append-block function body-block)
      (llvm/append-block function end-block)
      ;; branch from the current block to the condition block
      (llvm/build-br builder condition-block)
      (llvm/position-at-end builder condition-block)
      (let [state (assoc state :current-block condition-block)
            [state condition-value condition-type]
            (compile-expression state environment condition-form)]
        (when-not (= :i1 condition-type)
          (throw (ex-info (str "while condition must be a comparison, got " condition-type)
                          {:type condition-type})))
        (llvm/build-cond-br builder condition-value body-block end-block)
        (llvm/position-at-end builder body-block)
        (let [state (assoc state :current-block body-block)
              state (compile-body state environment body-forms)]
          (llvm/build-br builder condition-block)
          (llvm/position-at-end builder end-block)
          (let [state (assoc state :current-block end-block
                             :result-value nil :return-type nil)]
            [state nil nil]))))))

(defn- compile-expression
  [state environment expression]
  (cond
    (integer? expression) [state (llvm/const-int (:context state) expression) :i64]
    (float? expression) [state (llvm/const-real (:context state) expression) :f64]
    (symbol? expression) (compile-local-reference state environment expression)
    (seq? expression)
    (let [operator (first expression)
          {:keys [context module builder arena] :as state} state]
      (cond
        (#{'+ '- '* '/} operator) (compile-arithmetic state environment operator (rest expression))
        (#{'< '<= '> '>= '= 'not=} operator) (compile-comparison state environment operator (rest expression))
        (= operator 'to-float) (compile-to-float state environment expression)
        (= operator 'load) (compile-load state environment expression)
        (= operator 'store) (compile-store state environment expression)
        (= operator 'pointer-add) (compile-pointer-add state environment expression)
        (= operator 'to-pointer) (compile-to-pointer state environment expression)
        (= operator 'let) (compile-let state environment expression)
        (= operator 'set) (compile-set state environment expression)
        (= operator 'while) (compile-while state environment expression)
        :else (compile-procedure-call state environment operator (rest expression))))
    :else (throw (ex-info (str "Unsupported clodin expression: " expression)
                          {:expression expression}))))

(defn- compile-body
  "Compiles the body expressions in order and returns the emitter state
  extended with the result value and return type of the last expression.
  A body form that produces no value, such as set or while, leaves the
  result value nil."
  [state environment body-forms]
  (reduce (fn [state body-form]
            (let [[state value type] (compile-expression state environment body-form)]
              (assoc state :result-value value :return-type type)))
          (assoc state :result-value nil :return-type nil)
          body-forms))

(defn- expression-type
  "Returns the clodin type a form produces, or nil when there is none."
  [environment expression]
  (cond
    (integer? expression) :i64
    (float? expression) :f64
    (symbol? expression) (:type (get environment expression))
    (seq? expression)
    (let [operator (first expression)]
      (when (symbol? operator)
        (cond
          (#{'+ '- '* '/} operator) (expression-type environment (second expression))
          (#{'< '<= '> '>= '= 'not=} operator) :i1
          (= operator 'to-float) :f64
          (= operator 'load) (second expression)
          (= operator 'store) nil
          (= operator 'pointer-add) :ptr
          (= operator 'to-pointer) :ptr
          (= operator 'let)
          (let [[_ bindings & inner-body] expression
                environment (reduce (fn [env [name declared-type _]]
                                      (assoc env name {:type declared-type}))
                                    environment
                                    (partition 3 bindings))]
            (expression-type environment (last inner-body)))
          (= operator 'set) nil
          (= operator 'while) nil
          :else (let [procedure (get @procedure-registry (str operator))]
                  (:return-type procedure)))))
    :else nil))

;;; Procedure -> bitcode
;;; ---------------------------------------------------------------------------

(defn- build-procedure!
  "Builds the LLVM IR for a single procedure through the C API and returns
  {:bitcode byte[] :return-type ... :result-value ... :callees {...}}."
  [name param-names param-types return-type body-forms]
  (let [arena (Arena/ofConfined)
        context (llvm/new-context)
        module (llvm/new-module context (str name))
        function-type (llvm/function-type context arena return-type param-types)]
    (try
      (let [function (llvm/add-function module (llvm/c-string arena (str name)) function-type)
            builder (llvm/new-builder context)
            entry-block (llvm/append-entry-block function builder context arena "entry")
            state {:arena arena :context context :module module :builder builder
                   :function function :entry-block entry-block :current-block entry-block
                   :callees {}}
            environment (into {} (map (fn [param-name param-type index]
                                        [param-name {:type param-type
                                                     :value (llvm/get-param function index)
                                                     :kind :param}])
                                      param-names param-types (range)))
            state (compile-body state environment body-forms)
            result-value (:result-value state)
            effective-return-type (or return-type (:return-type state))]
        (if effective-return-type
          (do (when-not result-value
                (throw (ex-info (str "Procedure " name " has no value to return")
                                {:procedure name :return-type effective-return-type})))
              (llvm/build-ret builder result-value))
          (llvm/build-ret-void builder))
        (llvm/dispose-builder builder)
        (llvm/verify module arena)
        (let [bitcode (llvm/write-bitcode module)]
          {:bitcode bitcode
           :return-type effective-return-type
           :result-value result-value
           :callees (:callees state)}))
      (finally
        (llvm/dispose-module module)
        (llvm/dispose-context context)
        (.close arena)))))

(defn- register-procedure!
  "Registers the bitcode and signature of a procedure and marks the module
  dirty. The module is compiled later, once, when a native procedure is
  first called."
  [name param-names param-types return-type bitcode callees]
  (swap! procedure-registry assoc name
         {:param-types (vec param-types)
          :return-type return-type
          :bitcode bitcode
          :callees callees})
  (reset! module-dirty true)
  nil)

;;; ---------------------------------------------------------------------------
;;; Whole module build : linked bitcode -> in-process JIT
;;; ---------------------------------------------------------------------------

(defn- link-module
  "Parses every procedure's stored bitcode into a single linked module in
  the given context."
  [context arena]
  (when (empty? @procedure-registry)
    (throw (ex-info "No clodin procedures registered to compile" {})))
  (reduce (fn [merged {:keys [bitcode]}]
            (let [module (llvm/parse-bitcode context bitcode arena)]
              (if (nil? merged)
                module
                (llvm/link-into! merged module))))
          nil
          (vals @procedure-registry)))

(defn- build-handles
  "Builds a panama downcall handle for every registered procedure from its
  machine code address in the given JIT."
  [jit arena]
  (into {}
        (map (fn [[name {:keys [param-types return-type]}]]
               (let [address (llvm/lookup-address jit (llvm/c-string arena (str name)) arena)]
                 (when-not address
                   (throw (ex-info (str "JIT has no symbol for procedure " name)
                                   {:procedure name})))
                 [name (.downcallHandle (Linker/nativeLinker)
                                        (MemorySegment/ofAddress address)
                                        (function-descriptor return-type param-types)
                                        (make-array Linker$Option 0))])))
        @procedure-registry))

(defn- recompile-module!
  "Links every procedure's stored bitcode into one module, compiles it
  in-process with LLVM's ORC/LLJIT JIT, and rebuilds the panama downcall
  handles for the new addresses. The previous JIT is disposed once the new
  one is live."
  []
  (llvm/init-targets!)
  (let [arena (Arena/ofConfined)]
    (try
      (let [context (llvm/new-context)
            module (try (link-module context arena)
                        (catch Throwable error
                          (llvm/dispose-context context)
                          (throw error)))
            _ (llvm/verify module arena)
            jit (llvm/create-jit arena)
            _ (llvm/add-module-to-jit! jit module context)
            handles (build-handles jit arena)]
        (let [old-jit @current-jit]
          (reset! current-jit jit)
          (reset! procedure-handles handles)
          (when old-jit
            (try (llvm/dispose-jit old-jit) (catch Throwable _error)))))
      (finally
        (try (.close arena) (catch Throwable _error))))))

(defn compile-module!
  "Compiles and loads the accumulated module if a procedure registration
  marked it dirty. This batches all defproc forms evaluated since the last
  compile into one in-process JIT build."
  []
  (locking compile-lock
    (when @module-dirty
      (recompile-module!)
      (reset! module-dirty false))
    nil))

(defn invoke-procedure
  "Calls a native procedure through its panama downcall handle, coercing
  the clojure arguments to the declared types. If procedures have been
  registered since the last compile, the module is compiled and loaded
  first."
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
;;; Compilation entry point (macro)
;;; ---------------------------------------------------------------------------

(defn- defproc-form
  "Type-checks and generates the bitcode for the procedure described by a
  defproc form and returns the clojure defn form that calls it."
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
        parameter-environment (into {} (map (fn [name type]
                                              [name {:type type}])
                                            param-names param-types))
        inferred-return-type (expression-type parameter-environment (last body-forms))
        return-type (or explicit-return-type inferred-return-type)
        {:keys [bitcode callees]} (build-procedure! (str name) param-names param-types
                                                    return-type body-forms)]
    (register-procedure! (str name) param-names param-types return-type bitcode callees)
    `(defn ~name [~@param-names]
       (invoke-procedure ~(str name) [~@param-names]))))

(defmacro defproc
  "Defines a native clodin procedure.

  (defproc twice [number :i64] (* number 2))

  At macroexpansion time the body is type checked (untyped constant
  integers are 64 bit integers) and rendered as llvm ir through the LLVM C
  API. The bitcode is stored in the registry, but the JIT is not run yet.
  The whole module is compiled and loaded lazily in-process on the first
  call to any native procedure wrapper.

  An explicit return type can be given by putting a type keyword before
  the body forms; without it the return type is inferred from the last
  body expression.

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

(defproc sum-squares [limit :i64]
  (let [sum :i64 0
        index :i64 0]
    (while (< index limit)
      (let [square :i64 (* index index)]
        (set sum (+ sum square)))
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

(deftest test-let-inside-while
  (testing "a let inside a while is hoisted and reused"
    (is (= 14 (sum-squares 4)))
    (is (= 0 (sum-squares 0)))))

(defn -main
  [& _]
  (let [summary (run-tests 'clodin.compiler)]
    (when (or (pos? (:fail summary 0)) (pos? (:error summary 0)))
      (System/exit 1))))