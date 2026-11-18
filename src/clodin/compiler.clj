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

  Uniform element buffers are read and written with (buffer-get pointer
  type index) and (buffer-set pointer type index value), where index counts
  elements: the offset is index times the element size (8 bytes for
  :i64/:f64/:ptr, 4 bytes for :i32/:f32).

  External C functions are declared with extern, for example
  (extern printf [:ptr] :i32 :varargs). The JIT resolves the symbol from the
  host process, so libc functions are available without linking anything.
  Clojure string literals compile to constant C strings and are passed as
  :ptr.

  Standalone executables are built with
  (build-executable! 'my.namespace {:output \"my-program\"}), which links the
  procedures defined in my.namespace and the namespaces they call, then hands
  the object file to the system C linker. One of those namespaces must define
  a procedure named main.

  Local variables are introduced with (let [name type value ...] body...)
  and mutated with (set name value). Loops are written with
  (while condition body...).

  Math helpers: (sqrt x), (abs x), (min a b ...) and (max a b ...). sqrt
  and abs take floats; abs also accepts integers. min and max accept floats
  or integers, all operands sharing one type. They lower to the llvm.sqrt,
  llvm.fabs, llvm.minnum/llvm.maxnum and llvm.smin/llvm.smax intrinsics.

  (to-float x) converts an integer to a 64 bit float and (to-f32 x)
  converts an integer or a 64 bit float to a 32 bit float. (to-i32 x)
  truncates a 64 bit integer to 32 bits. Float literals are 64 bit, so f32
  constants are written (to-f32 1.0e-9).

  (if condition then else) chooses between two same-typed value expressions
  with an llvm.select, so both branches must be pure."
  (:require [clodin.llvm-api :as llvm]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
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

;;; external-registry maps an external C function name to
;;; {:param-types [...] :return-type ... :varargs? bool}. External functions
;;; are declared with the extern macro and resolved by the JIT from the host
;;; process, so they need no bitcode of their own.
(def external-registry (atom {}))

(def ^:private supported-types #{:i64 :i32 :f64 :f32 :ptr})

(defn register-external!
  "Registers the signature of an external C function so clodin procedures can
  call it. The function is resolved by the JIT from the host process at call
  time. varargs? declares a C variadic function such as printf."
  [name param-types return-type varargs?]
  (when-not (symbol? name)
    (throw (ex-info "External function name must be a symbol" {:name name})))
  (when-not (vector? param-types)
    (throw (ex-info "External function parameter types must be a vector"
                    {:name name :param-types param-types})))
  (doseq [type (concat param-types (when return-type [return-type]))]
    (when-not (contains? supported-types type)
      (throw (ex-info (str "Unsupported external function type: " type)
                      {:name name :type type}))))
  (when (contains? @procedure-registry (str name))
    (throw (ex-info (str "External function " name
                         " conflicts with a clodin procedure of the same name")
                    {:name name})))
  (let [signature {:param-types (vec param-types)
                   :return-type return-type
                   :varargs? (boolean varargs?)}]
    (when-let [existing (get @external-registry (str name))]
      (when-not (= existing signature)
        (throw (ex-info (str "External function " name
                             " was already declared with a different signature")
                        {:name name :existing existing :signature signature}))))
    (swap! external-registry assoc (str name) signature))
  nil)

(defmacro extern
  "Declares an external C function so clodin procedures can call it.

  (extern printf [:ptr] :i32 :varargs)
  (extern strlen [:ptr] :i64)

  param-types lists the fixed argument types. A trailing :varargs makes the
  function variadic, so calls may pass extra arguments after the fixed ones.
  A return type of :void (or nil) declares a function that returns nothing.
  The symbol is resolved by the JIT from the host process, so libc functions
  such as printf and strlen are available without linking anything."
  [name param-types return-type & options]
  (doseq [option options]
    (when-not (= option :varargs)
      (throw (ex-info (str "Unknown extern option: " option)
                      {:option option}))))
  (register-external! name param-types
                      (when-not (= :void return-type) return-type)
                      (boolean (some #{:varargs} options)))
  nil)

(def procedure-handles (atom {}))
(def module-dirty (atom true))
(defonce current-jit (atom nil))
(defonce compile-lock (Object.))

;;; The LLVM IR optimization level used when a module is built, one of
;;; :none (JIT the IR as emitted, the default) or :O0/:O1/:O2/:O3. The
;;; pipeline only optimizes, never changes IEEE floating point results, so
;;; all levels produce the same numerical output.
;;;
(def ^:private optimization-level (atom :O3 #_:none))

(defn set-optimization-level!
  "Sets the LLVM IR optimization level used on the next module build and
  forces a recompile. Levels: :none, :O0, :O1, :O2, :O3."
  [level]
  (when-not (contains? #{:none :O0 :O1 :O2 :O3} level)
    (throw (ex-info (str "Unknown optimization level: " level) {:level level})))
  (reset! optimization-level level)
  (reset! module-dirty true)
  nil)

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

(defn- promote-variadic-argument
  "Applies the C default argument promotion to a variadic argument. Clodin
  has no types narrower than 32 bits, so only f32 needs promoting to f64."
  [state value type]
  (if (= :f32 type)
    [(llvm/build-fpext (:builder state) value
                       (llvm/type-for (:context state) :f64)
                       (:arena state) nil)
     :f64]
    [value type]))

(defn- compile-external-call
  "Compiles a call to an external C function declared with extern. The
  function is declared in the module and resolved by the JIT from the host
  process. Variadic arguments are promoted the way a C compiler would."
  [state environment procedure-symbol
   {:keys [param-types return-type varargs?]} argument-forms]
  (let [fixed-count (count param-types)
        argument-count (count argument-forms)]
    (when (or (< argument-count fixed-count)
              (and (not varargs?) (not= argument-count fixed-count)))
      (throw (ex-info (str "External function " procedure-symbol " expects "
                           (if varargs?
                             (str "at least " fixed-count)
                             (str fixed-count))
                           " arguments, got " argument-count)
                      {:procedure procedure-symbol
                       :param-types param-types
                       :varargs? varargs?})))
    (let [[state argument-values argument-types]
          (compile-operands state environment argument-forms)
          [state argument-values argument-types]
          (if varargs?
            (reduce (fn [[state values types] index]
                      (if (< index fixed-count)
                        [state values types]
                        (let [[value type]
                              (promote-variadic-argument state (nth values index)
                                                         (nth types index))]
                          [state (assoc values index value)
                           (assoc types index type)])))
                    [state (vec argument-values) (vec argument-types)]
                    (range argument-count))
            [state (vec argument-values) (vec argument-types)])
          fixed-types (subvec argument-types 0 fixed-count)]
      (when-not (= fixed-types param-types)
        (throw (ex-info (str "Argument types " fixed-types
                             " do not match fixed parameters " param-types
                             " for external function " procedure-symbol)
                        {:procedure procedure-symbol
                         :argument-types fixed-types})))
      (let [{:keys [context module builder arena]} state
            function-type (llvm/function-type context arena return-type
                                              param-types varargs?)
            callee (llvm/get-or-add-function
                    module (llvm/c-string arena (str procedure-symbol))
                    function-type)
            result (llvm/build-call builder function-type callee
                                    argument-values arena)]
        (if return-type
          [state result return-type]
          [state nil nil])))))

(defn- compile-call
  "Dispatches a call to either a clodin procedure or an external C function."
  [state environment procedure-symbol argument-forms]
  (let [name (str procedure-symbol)]
    (cond
      (contains? @procedure-registry name)
      (compile-procedure-call state environment procedure-symbol argument-forms)

      (contains? @external-registry name)
      (compile-external-call state environment procedure-symbol
                             (get @external-registry name) argument-forms)

      :else
      (throw (ex-info (str "Unknown clodin procedure: " procedure-symbol)
                      {:procedure procedure-symbol})))))

(defn- compile-sqrt
  "Compiles (sqrt x), the square root of a floating point value, lowered to
  the llvm.sqrt intrinsic."
  [state environment expression]
  (when-not (= 2 (count expression))
    (throw (ex-info "sqrt expects exactly one operand" {:expression expression})))
  (let [[state value type] (compile-expression state environment (second expression))]
    (when-not (float-type? type)
      (throw (ex-info (str "sqrt expects a float operand, got " type) {:type type})))
    (let [result (llvm/build-intrinsic-fcall state "llvm.sqrt" type [value])]
      [state result type])))

(defn- compile-abs
  "Compiles (abs x), the absolute value. Floats lower to the llvm.fabs
  intrinsic; integers are expanded with a sign test and select."
  [state environment expression]
  (when-not (= 2 (count expression))
    (throw (ex-info "abs expects exactly one operand" {:expression expression})))
  (let [[state value type] (compile-expression state environment (second expression))]
    (cond
      (float-type? type)
      (let [result (llvm/build-intrinsic-fcall state "llvm.fabs" type [value])]
        [state result type])

      (#{:i64 :i32} type)
      (let [builder (:builder state)
            arena (:arena state)
            zero (llvm/const-int-of-type (:context state) type 0)
            negated (llvm/build-int-sub builder zero value nil arena)
            negative? (llvm/build-icmp builder '< value zero arena)
            result (llvm/build-select builder negative? negated value arena)]
        [state result type])

      :else
      (throw (ex-info (str "abs expects a number, got " type) {:type type})))))

(defn- compile-min-or-max
  "Compiles (min a b ...) and (max a b ...), folding the operands left to
  right. Floats lower to llvm.minnum/llvm.maxnum; integers to
  llvm.smin/llvm.smax. All operands must have the same type."
  [state environment operator operand-forms]
  (when (< (count operand-forms) 2)
    (throw (ex-info (str operator " needs at least 2 operands") {:operator operator})))
  (let [[state values types] (compile-operands state environment operand-forms)
        distinct-types (distinct types)]
    (when-not (= 1 (count distinct-types))
      (throw (ex-info (str "Mixed operand types " distinct-types
                           " for operator " operator)
                      {:operator operator :types types})))
    (let [argument-type (first distinct-types)
          prefix (if (float-type? argument-type)
                   (if (= operator 'min) "llvm.minnum" "llvm.maxnum")
                   (if (= operator 'min) "llvm.smin" "llvm.smax"))
          result (reduce (fn [accumulate value]
                           (llvm/build-intrinsic-fcall state prefix argument-type
                                                       [accumulate value]))
                         (first values) (rest values))]
      [state result argument-type])))

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

(defn- compile-to-f32
  "Compiles (to-f32 x), a cast of an integer or a 64 bit float value to a
  32 bit float."
  [state environment expression]
  (when-not (= 2 (count expression))
    (throw (ex-info "to-f32 expects exactly one operand" {:expression expression})))
  (let [[state value type] (compile-expression state environment (second expression))]
    (case type
      :f64 (let [result (llvm/build-fptrunc (:builder state) value
                                            (llvm/type-for (:context state) :f32)
                                            (:arena state) nil)]
             [state result :f32])
      (:i64 :i32) (let [result (llvm/build-sitofp (:builder state) value
                                                  (llvm/type-for (:context state) :f32)
                                                  (:arena state) nil)]
                    [state result :f32])
      (throw (ex-info (str "to-f32 expects an integer or a 64 bit float, got " type)
                      {:type type})))))

(defn- compile-to-i32
  "Compiles (to-i32 x), truncating a 64 bit integer to 32 bits."
  [state environment expression]
  (when-not (= 2 (count expression))
    (throw (ex-info "to-i32 expects exactly one operand" {:expression expression})))
  (let [[state value type] (compile-expression state environment (second expression))]
    (case type
      :i32 [state value :i32]
      :i64 (let [result (llvm/build-trunc (:builder state) value
                                          (llvm/type-for (:context state) :i32)
                                          (:arena state) nil)]
             [state result :i32])
      (throw (ex-info (str "to-i32 expects an integer, got " type)
                      {:type type})))))

(defn- compile-string-literal
  "Compiles a Clojure string literal into a private constant null terminated
  UTF-8 global and returns its address as a :ptr."
  [state value]
  (let [index (:string-counter state)
        global-name (str ".clodin." (:procedure-name state) ".string." index)
        global (llvm/build-global-string (:context state) (:module state)
                                         (:arena state) value global-name)]
    [(update state :string-counter inc) global :ptr]))

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

(defn- element-size
  "The size in bytes of one buffer element of the given type."
  [type]
  (case type
    :i64 8
    :i32 4
    :f64 8
    :f32 4
    :ptr 8
    (throw (ex-info (str "No buffer element size for type " type) {:type type}))))

(defn- compile-buffer-get
  "Compiles (buffer-get pointer type index) into a load from the pointer
  scaled by the element size of type, so index counts elements, not bytes."
  [state environment expression]
  (when-not (= 4 (count expression))
    (throw (ex-info "buffer-get expects (buffer-get pointer type index)"
                    {:expression expression})))
  (let [[pointer-form type index-form] (rest expression)
        size (element-size type)
        [state pointer-value pointer-type]
        (compile-expression state environment pointer-form)]
    (when-not (= :ptr pointer-type)
      (throw (ex-info (str "buffer-get expects a :ptr pointer, got " pointer-type)
                      {:type pointer-type})))
    (let [[state index-value index-type]
          (compile-expression state environment index-form)]
      (when-not (#{:i64 :i32} index-type)
        (throw (ex-info (str "buffer-get expects an integer index, got " index-type)
                        {:type index-type})))
      (let [builder (:builder state)
            context (:context state)
            arena (:arena state)
            offset (llvm/build-int-mul builder index-value
                                       (llvm/const-int-of-type context index-type size)
                                       nil arena)
            element-pointer (llvm/build-bytes-gep state pointer-value offset)
            result (llvm/build-load builder (llvm/type-for context type)
                                    element-pointer arena nil)]
        [state result type]))))

(defn- compile-buffer-set
  "Compiles (buffer-set pointer type index value) into a store to the pointer
  scaled by the element size of type, so index counts elements, not bytes.
  A buffer-set does not produce a value."
  [state environment expression]
  (when-not (= 5 (count expression))
    (throw (ex-info "buffer-set expects (buffer-set pointer type index value)"
                    {:expression expression})))
  (let [[pointer-form type index-form value-form] (rest expression)
        size (element-size type)
        [state pointer-value pointer-type]
        (compile-expression state environment pointer-form)]
    (when-not (= :ptr pointer-type)
      (throw (ex-info (str "buffer-set expects a :ptr pointer, got " pointer-type)
                      {:type pointer-type})))
    (let [[state index-value index-type]
          (compile-expression state environment index-form)]
      (when-not (#{:i64 :i32} index-type)
        (throw (ex-info (str "buffer-set expects an integer index, got " index-type)
                        {:type index-type})))
      (let [[state stored-value stored-type]
            (compile-expression state environment value-form)]
        (when-not (= type stored-type)
          (throw (ex-info (str "buffer-set expects a " type " value, got " stored-type)
                          {:element-type type :value-type stored-type})))
        (let [builder (:builder state)
              context (:context state)
              arena (:arena state)
              offset (llvm/build-int-mul builder index-value
                                         (llvm/const-int-of-type context index-type size)
                                         nil arena)
              element-pointer (llvm/build-bytes-gep state pointer-value offset)]
          (llvm/build-store builder stored-value element-pointer)
          [state nil nil])))))

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

(defn- compile-if
  "Compiles (if condition then else) with an llvm.select instruction, so it
  is a value-producing expression. Both branches are compiled as values of
  the same type and select evaluates both operands, so the branches must be
  pure expressions without side effects."
  [state environment expression]
  (when-not (= 4 (count expression))
    (throw (ex-info "if expects (if condition then else)" {:expression expression})))
  (let [[condition-form then-form else-form] (rest expression)
        [state condition-value condition-type]
        (compile-expression state environment condition-form)]
    (when-not (= :i1 condition-type)
      (throw (ex-info (str "if condition must be a comparison, got " condition-type)
                      {:type condition-type})))
    (let [[state then-value then-type] (compile-expression state environment then-form)
          [state else-value else-type] (compile-expression state environment else-form)]
      (when-not (= then-type else-type)
        (throw (ex-info (str "if branches must have the same type, got " then-type " and " else-type)
                        {:then-type then-type :else-type else-type})))
      (let [result (llvm/build-select (:builder state) condition-value
                                      then-value else-value (:arena state))]
        [state result then-type]))))

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
    (string? expression) (compile-string-literal state expression)
    (symbol? expression) (compile-local-reference state environment expression)
    (seq? expression)
    (let [operator (first expression)
          {:keys [context module builder arena] :as state} state]
      (cond
        (#{'+ '- '* '/} operator) (compile-arithmetic state environment operator (rest expression))
        (#{'< '<= '> '>= '= 'not=} operator) (compile-comparison state environment operator (rest expression))
        (= operator 'to-float) (compile-to-float state environment expression)
        (= operator 'to-f32) (compile-to-f32 state environment expression)
        (= operator 'to-i32) (compile-to-i32 state environment expression)
        (= operator 'sqrt) (compile-sqrt state environment expression)
        (= operator 'abs) (compile-abs state environment expression)
        (#{'min 'max} operator) (compile-min-or-max state environment operator (rest expression))
        (= operator 'load) (compile-load state environment expression)
        (= operator 'store) (compile-store state environment expression)
        (= operator 'pointer-add) (compile-pointer-add state environment expression)
        (= operator 'buffer-get) (compile-buffer-get state environment expression)
        (= operator 'buffer-set) (compile-buffer-set state environment expression)
        (= operator 'to-pointer) (compile-to-pointer state environment expression)
        (= operator 'let) (compile-let state environment expression)
        (= operator 'set) (compile-set state environment expression)
        (= operator 'if) (compile-if state environment expression)
        (= operator 'while) (compile-while state environment expression)
        :else (compile-call state environment operator (rest expression))))
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
    (string? expression) :ptr
    (symbol? expression) (:type (get environment expression))
    (seq? expression)
    (let [operator (first expression)]
      (when (symbol? operator)
        (cond
          (#{'+ '- '* '/} operator) (expression-type environment (second expression))
          (#{'< '<= '> '>= '= 'not=} operator) :i1
          (= operator 'to-float) :f64
          (= operator 'to-f32) :f32
          (= operator 'to-i32) :i32
          (#{'sqrt 'abs} operator) (expression-type environment (second expression))
          (#{'min 'max} operator) (expression-type environment (second expression))
          (= operator 'load) (second expression)
          (= operator 'store) nil
          (= operator 'pointer-add) :ptr
          (= operator 'buffer-get) (nth expression 2)
          (= operator 'buffer-set) nil
          (= operator 'to-pointer) :ptr
          (= operator 'let)
          (let [[_ bindings & inner-body] expression
                environment (reduce (fn [env [name declared-type _]]
                                      (assoc env name {:type declared-type}))
                                    environment
                                    (partition 3 bindings))]
            (expression-type environment (last inner-body)))
          (= operator 'set) nil
          (= operator 'if) (expression-type environment (nth expression 2))
          (= operator 'while) nil
          :else (let [procedure (get @procedure-registry (str operator))
                      external (get @external-registry (str operator))]
                  (or (:return-type procedure)
                      (:return-type external))))))
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
                   :procedure-name name :string-counter 0
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
  "Registers the bitcode, signature and defining namespace of a procedure and
  marks the module dirty. The module is compiled later, once, when a native
  procedure is first called."
  [namespace name param-names param-types return-type bitcode callees]
  (when (contains? @external-registry name)
    (throw (ex-info (str "Procedure " name
                         " conflicts with an external function of the same name")
                    {:procedure name})))
  (swap! procedure-registry assoc name
         {:namespace namespace
          :param-types (vec param-types)
          :return-type return-type
          :bitcode bitcode
          :callees callees})
  (reset! module-dirty true)
  nil)

;;; ---------------------------------------------------------------------------
;;; Whole module build : linked bitcode -> in-process JIT
;;; ---------------------------------------------------------------------------

(defn- link-procedures
  "Parses the stored bitcode of the given procedures into a single linked
  module in the given context."
  [procedures context arena]
  (when (empty? procedures)
    (throw (ex-info "No clodin procedures to link" {})))
  (reduce (fn [merged {:keys [bitcode]}]
            (let [module (llvm/parse-bitcode context bitcode arena)]
              (if (nil? merged)
                module
                (llvm/link-into! merged module))))
          nil
          (vals procedures)))

(defn- link-module
  "Parses every registered procedure's stored bitcode into a single linked
  module in the given context."
  [context arena]
  (when (empty? @procedure-registry)
    (throw (ex-info "No clodin procedures registered to compile" {})))
  (link-procedures @procedure-registry context arena))

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
            _ (when-not (= :none @optimization-level)
                (llvm/optimize-module! module arena @optimization-level))
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
;;; Standalone executable build
;;; ---------------------------------------------------------------------------

(defn- namespace-closure
  "Returns the set of namespaces reachable from root-namespace by following
  procedure calls. A procedure's callees are recorded when it is compiled, so
  the closure covers exactly the namespaces whose procedures are used."
  [root-namespace]
  (let [registry @procedure-registry
        namespace-of (fn [procedure-name]
                       (:namespace (get registry procedure-name)))
        callee-namespaces (fn [[_procedure-name procedure]]
                            (keep namespace-of (keys (:callees procedure))))]
    (loop [pending [root-namespace]
           seen #{}]
      (if-let [namespace (first pending)]
        (if (contains? seen namespace)
          (recur (rest pending) seen)
          (let [procedures (filter (fn [[_procedure-name procedure]]
                                     (= namespace (:namespace procedure)))
                                   registry)
                next-namespaces (mapcat callee-namespaces procedures)]
            (recur (concat (rest pending) next-namespaces)
                   (conj seen namespace))))
        seen))))

(defn- procedures-in-namespaces
  "Returns the registry entries whose defining namespace is in namespaces."
  [namespaces]
  (into {}
        (filter (fn [[_procedure-name procedure]]
                  (contains? namespaces (:namespace procedure))))
        @procedure-registry))

(defn- main-namespace
  "The namespace that defines the procedure named main, or nil."
  []
  (:namespace (get @procedure-registry "main")))

(defn build-executable!
  "Builds a standalone native executable from the procedures defined in
  root-namespace and the namespaces they call into. One of those namespaces
  must define a procedure named main.

  root-namespace is required first, so its defproc forms register their
  bitcode. The procedure call graph then selects the namespaces to link. If
  main lives in a namespace that is loaded but not called from root-namespace,
  its namespace is added so the entry point is always present.

  Options:
    :output              output path (default \"a.out\")
    :optimization-level  :none, :O0, :O1, :O2 or :O3 (defaults to the
                         compiler's current level)
    :linker              linker driver command (default \"cc\")
    :linker-args         extra arguments passed to the linker driver
    :extra-namespaces    additional namespaces to link, for procedures that
                         are not reachable from root-namespace by calls

  Returns the output path. The intermediate object file is removed after a
  successful link."
  ([root-namespace]
   (build-executable! root-namespace {}))
  ([root-namespace {:keys [output linker linker-args extra-namespaces]
                    :or {output "a.out" linker "cc" linker-args []
                         extra-namespaces []}
                    :as options}]
   (let [root-namespace (symbol (str root-namespace))
         extra-namespaces (mapv #(symbol (str %)) extra-namespaces)]
     (doseq [namespace (cons root-namespace extra-namespaces)]
       (require namespace))
     (let [root-name (str root-namespace)
           root-closure (reduce (fn [closure namespace]
                                  (into closure (namespace-closure (str namespace))))
                                (namespace-closure root-name)
                                extra-namespaces)
           entry-namespace (main-namespace)
           namespaces (if (and entry-namespace
                              (not (contains? root-closure entry-namespace)))
                        (into root-closure (namespace-closure entry-namespace))
                        root-closure)
           procedures (procedures-in-namespaces namespaces)]
       (when (empty? procedures)
         (throw (ex-info (str "No clodin procedures found in " root-name
                              " or the namespaces it calls")
                         {:namespace root-name})))
       (when-not (contains? procedures "main")
         (throw (ex-info (str "No procedure named main in " root-name
                              " or the namespaces it calls")
                         {:namespace root-name
                          :namespaces namespaces})))
       (llvm/init-targets!)
       (let [arena (Arena/ofConfined)]
         (try
           (let [context (llvm/new-context)
                 module (link-procedures procedures context arena)
                 target-machine (llvm/create-target-machine arena)]
             (try
               (llvm/set-module-target! module target-machine arena)
               (llvm/verify module arena)
               (let [level (or (:optimization-level options) @optimization-level)]
                 (when-not (= :none level)
                   (llvm/optimize-module! module arena level)))
               (let [object-path (str output ".o")]
                 (io/make-parents output)
                 (llvm/emit-object-file! target-machine module object-path arena)
                 (let [{:keys [exit err]}
                       (apply shell/sh
                              (concat [linker "-o" output object-path] linker-args))]
                   (when-not (zero? exit)
                     (throw (ex-info (str "Linking failed: " err)
                                     {:exit exit :err err :output output})))
                   (io/delete-file object-path true)
                   output))
             (finally
               (llvm/dispose-target-machine target-machine)
               (llvm/dispose-module module)
               (llvm/dispose-context context))))
           (finally
             (.close arena))))))))

;;; ---------------------------------------------------------------------------
;;; Compilation entry point (macro)
;;; ---------------------------------------------------------------------------

(defn- defproc-form
  "Type-checks and generates the bitcode for the procedure described by a
  defproc form and returns the clojure defn form that calls it. namespace is
  the namespace the procedure is defined in, used by build-executable! to
  select procedures."
  [namespace name params body]
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
    (register-procedure! namespace (str name) param-names param-types return-type bitcode callees)
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
  (defproc-form (str *ns*) name params body))

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

(defproc sqrt-of [x :f64]
  (sqrt x))

(defproc abs-float [x :f64]
  (abs x))

(defproc abs-int [x :i64]
  (abs x))

(defproc min-two [a :f64 b :f64]
  (min a b))

(defproc max-three [a :f64 b :f64 c :f64]
  (max a b c))

(defproc min-int [a :i64 b :i64]
  (min a b))

(defproc max-int [a :i64 b :i64]
  (max a b))

(defproc fill-buffer-f64 [pointer :ptr value :f64 count :i64]
  (let [index :i64 0]
    (while (< index count)
      (buffer-set pointer :f64 index value)
      (set index (+ index 1)))))

(defproc sum-buffer-f64 [pointer :ptr count :i64]
  (let [sum :f64 0.0
        index :i64 0]
    (while (< index count)
      (set sum (+ sum (buffer-get pointer :f64 index)))
      (set index (+ index 1)))
    sum))

(defproc buffer-i32-at [pointer :ptr index :i64]
  (buffer-get pointer :i32 index))

(defproc buffer-f64-at [pointer :ptr index :i64]
  (buffer-get pointer :f64 index))

(defproc buffer-i32-set-at [pointer :ptr index :i64 value :i32]
  (buffer-set pointer :i32 index value))

(deftest test-buffer-get-set
  (let [arena (Arena/ofShared)
        buffer (.allocate arena 40)]
    (try
      (testing "f64 elements are 8 bytes apart"
        (fill-buffer-f64 buffer 2.5 4)
        (is (= 10.0 (sum-buffer-f64 buffer 4)))
        (is (= 2.5 (.getAtIndex buffer ValueLayout/JAVA_DOUBLE 3))))

      (testing "i32 elements are 4 bytes apart"
        (buffer-i32-set-at buffer 1 33)
        (buffer-i32-set-at buffer 2 44)
        (is (= 33 (buffer-i32-at buffer 1)))
        (is (= 44 (buffer-i32-at buffer 2)))
        (is (= 33 (.getAtIndex buffer ValueLayout/JAVA_INT 1)))
        (is (= 44 (.getAtIndex buffer ValueLayout/JAVA_INT 2)))
        (is (= 0 (.getAtIndex buffer ValueLayout/JAVA_INT 0))))

      (testing "f64 and i32 views address the same bytes"
        ;; f64 index 1 occupies bytes 8-15, which are exactly i32 indices 2
        ;; and 3, so a write through the i32 view must be visible through the
        ;; f64 view (little-endian: i32 2 is the low half, i32 3 the high).
        (fill-buffer-f64 buffer 0.0 2)
        (buffer-i32-set-at buffer 2 123456789)
        (buffer-i32-set-at buffer 3 -987654321)
        (is (= 123456789 (buffer-i32-at buffer 2)))
        (is (= -987654321 (buffer-i32-at buffer 3)))
        (is (= 123456789 (.getAtIndex buffer ValueLayout/JAVA_INT 2)))
        (is (= -987654321 (.getAtIndex buffer ValueLayout/JAVA_INT 3)))
        (is (= (.getAtIndex buffer ValueLayout/JAVA_LONG 1)
               (Double/doubleToRawLongBits (buffer-f64-at buffer 1)))))
      (finally
        (.close arena)))))

(deftest test-math-intrinsics
  (testing "sqrt"
    (is (= 3.0 (sqrt-of 9.0))))

  (testing "abs"
    (is (= 4.0 (abs-float -4.0)))
    (is (= 7 (abs-int -7))))

  (testing "min/max"
    (is (= 2.0 (min-two 5.0 2.0)))
    (is (= 9.0 (max-three 1.0 9.0 3.0)))
    (is (= 3 (min-int 7 3)))
    (is (= 8 (max-int 8 2)))))

(deftest test-substract-one
  (is (= 9 (substract-one 10))))

(deftest test-add-some
  (is (= 43 (add-some 1))))

(deftest test-add-both
  (is (= 7 (add-both 3 4)))
  (is (= -2 (add-both 2 -4))))

(deftest test-procedure-namespace
  (testing "defproc records the namespace that defines the procedure"
    (is (= "clodin.compiler"
           (:namespace (get @procedure-registry "add-both"))))))

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

;;; if and to-f32 support
(defproc if-choose [a :i64 b :i64]
  (if (< a b) a b))

(defproc choice-abs [x :f64]
  (if (< x 0.0) (abs x) x))

(defproc halve-f32 [x :f32]
  (/ x (to-f32 2)))

(defproc f32-from-f64 [x :f64]
  (to-f32 x))

(defproc f32-from-i64 [x :i64]
  (to-f32 x))

(deftest test-if
  (is (= 2 (if-choose 2 5)))
  (is (= 2 (if-choose 5 2)))
  (is (= 3.0 (choice-abs -3.0)))
  (is (= 3.0 (choice-abs 3.0))))

(deftest test-to-f32
  (is (= 2.5 (halve-f32 5.0)))
  (is (= 1.5 (f32-from-f64 1.5)))
  (is (= 3.0 (f32-from-i64 3))))

;;; External C functions
;;;
;;; extern registers a C function signature. The JIT resolves the symbol from
;;; the host process, so libc is available without linking anything. String
;;; literals compile to private constant globals and are passed as :ptr.

(extern printf [:ptr] :i32 :varargs)
(extern sprintf [:ptr :ptr] :i32 :varargs)
(extern strlen [:ptr] :i64)
(extern srand [:i32] :void)

(defproc print-nothing []
  (printf ""))

(defproc string-length-of-hello []
  (strlen "hello"))

(defproc string-length-of-empty []
  (strlen ""))

(defproc format-value-into-buffer [buffer :ptr]
  (sprintf buffer "value=%ld" 12345))

(defproc format-f32-into-buffer [buffer :ptr value :f32]
  (sprintf buffer "%.1f" value))

(defproc print-value [value :i64]
  (printf "value = %ld\n" value))

(defproc truncate-to-i32 [value :i64]
  (to-i32 value))

(defproc seed-random [seed :i32]
  (srand seed))

(deftest test-extern-printf
  (testing "printf is callable and returns the number of bytes written"
    (is (= 0 (print-nothing)))))

(deftest test-extern-strlen
  (testing "string literals are null terminated C strings"
    (is (= 5 (string-length-of-hello)))
    (is (= 0 (string-length-of-empty)))))

(deftest test-extern-sprintf
  (let [arena (Arena/ofShared)
        buffer (.allocate arena 64)]
    (try
      (is (= 11 (format-value-into-buffer buffer)))
      (is (= "value=12345" (.getString buffer 0)))
      (is (= 3 (format-f32-into-buffer buffer 2.5)))
      (is (= "2.5" (.getString buffer 0)))
      (finally
        (.close arena)))))

(deftest test-to-i32
  (testing "to-i32 truncates a 64 bit integer"
    (is (= 42 (truncate-to-i32 42)))
    (is (= 0 (truncate-to-i32 4294967296)))))

(deftest test-extern-void-return
  (testing "a void external function produces no value"
    (is (nil? (seed-random 1)))))

(deftest test-optimization-level
  (testing "set-optimization-level! preserves results at every level"
    (try
      (set-optimization-level! :O3)
      (is (= 2.5 (halve 5.0)))
      (is (= 7 (add-both 3 4)))
      (finally
        (set-optimization-level! :none))))
  (testing "rejects unknown levels"
    (is (thrown? clojure.lang.ExceptionInfo
                 (set-optimization-level! :O9)))))

(defn -main
  [& _]
  (let [summary (run-tests 'clodin.compiler)]
    (when (or (pos? (:fail summary 0)) (pos? (:error summary 0)))
      (System/exit 1))))
