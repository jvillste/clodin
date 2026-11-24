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

  Untyped constant integers are treated as 64 bit integers. Booleans use the
  :boolean type: comparisons produce :boolean, true and false are :boolean
  literals, and :boolean can be used for locals, parameters, returns and
  buffer elements.

  There is no not= form: write (not (= a b)), which composes the not
  procedure with =.

  Pointers use the :ptr type. Memory is accessed with
  (load type pointer), (store type pointer value), (pointer-add pointer
  offset) and (to-pointer integer-address).

  Uniform element buffers are read and written with (buffer-get pointer
  type index) and (buffer-set pointer type index value), where index counts
  elements: the offset is index times the element size (8 bytes for
  :i64/:f64/:ptr, 4 bytes for :i32/:f32, 1 byte for :boolean).

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

  Every procedure and global is registered under a qualified name,
  namespace/name, and its native symbol carries the namespace too, as
  namespace.name. A reference resolves to the namespace the procedure is
  defined in first, then to clodin.core, so only core names are visible
  without a namespace. A procedure that lives elsewhere is called by its
  qualified name:

  (defproc my.ns/report [value :i64] :void
    (clodin.runtime/print-i64 value))

  There is no require, refer or alias form yet. A definition may shadow a
  built in form such as print or stack-allocate, but an unqualified reference
  always means the form, so the procedure is then reachable only by its
  qualified name; defproc and defvar warn when that happens.

  Math helpers: (sqrt x), (abs x), (min a b ...) and (max a b ...). sqrt
  and abs take floats; abs also accepts integers. min and max accept floats
  or integers, all operands sharing one type. They lower to the llvm.sqrt,
  llvm.fabs, llvm.minnum/llvm.maxnum and llvm.smin/llvm.smax intrinsics.

  (to-float x) converts an integer to a 64 bit float and (to-f32 x)
  converts an integer or a 64 bit float to a 32 bit float. (to-i32 x)
  truncates a 64 bit integer to 32 bits and (to-i64 x) sign extends a 32 bit
  integer to 64 bits. Float literals are 64 bit, so f32 constants are written
  (to-f32 1.0e-9).

  (if condition then else) is a real branch, like Clojure's if: only the taken
  arm runs, so an arm may have side effects. In value position both arms must
  produce a value of the same type, which the if produces, merged with a phi
  node; mixing a value arm with a statement arm is an error. In statement
  position, where the if's own value is discarded, both arms are compiled as
  statements, so their values are dropped and the arms need not agree in type.
  A body form that is not the last one is in statement position, as are the
  bodies of when and while, and the body of a procedure declared :void.

  (when condition body...) runs its body only when the condition is true and
  produces no value; use if when a value is needed.

  (not value) negates a :boolean and (is-null pointer) tests address zero.
  Both are procedures in clodin.core, written in clodin itself like
  clojure.core, not compiler forms; at :O3 LLVM inlines them, so not costs
  the same single xor instruction as the form did.

  Raw memory is reached through (load type pointer), (store type pointer
  value), (pointer-add pointer offset), (buffer-get pointer type index) and
  (buffer-set pointer type index value). (stack-allocate byte-count alignment)
  reserves an uninitialised block on the stack and yields a :ptr to it, one
  block per call however often a loop runs it. (pointer-call pointer
  [param-types] return-type arguments...) calls a function address with its
  signature written at the call site, because a :ptr carries no type.

  Top level mutable state is declared with (defvar name type literal), for
  example (defvar running? :boolean true). In clodin source the name reads as
  a value and (set name value) writes it. Access is atomic on both sides, so a
  native loop that spins on a global sees a write from another thread. The
  name is also bound in Clojure to a handle implementing clojure.lang.IDeref
  and clojure.lang.IAtom2, so @running?, (reset! running? false) and
  (swap! counter inc) work from the REPL.

  (print ...) and (println ...) write to *out* through a JVM upcall on the
  calling thread, so with-out-str and CIDER capture them. Operands are
  concatenated without separators; add spaces explicitly. A string literal
  and a :ptr are written as text, numbers are formatted with snprintf, and a
  :boolean writes true or false. (println) appends a newline. extern printf
  stays raw libc output and goes to the process stdout.

  print and println are forms, not procedures: they are variadic and lowered
  per operand type to calls to the procedures in the clodin.runtime
  namespace, which is written in clodin and loaded on demand. Its
  clodin-write global holds the JVM upcall address, so an offline build leaves
  it null and the runtime falls back to write(1, ...)."
  (:require [clodin.globals :as globals]
            [clodin.llvm-api :as llvm]
            [clodin.names :as names]
            [clodin.print :as print]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as string]
            [clojure.test :refer [deftest is run-tests testing]])
  (:import (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemoryLayout MemorySegment SymbolLookup ValueLayout)
           (java.lang.invoke MethodHandles MethodType)))

(declare compile-expression compile-body compile-statement)

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

;; The build mode. :monolithic relinks every procedure into one module on
;; each build; :reloadable gives every reloadable procedure a stable stub and
;; installs a new body module per compilation. See todo.md.
(defonce build-mode (atom :monolithic))

;; Process-wide counter for the body symbols foo$1, foo$2, ... so a body
;; symbol is never reused within a session.
(defonce body-counter (atom 0))

;; installed-stubs maps a stub symbol to its JIT addresses and handles. A stub
;; is installed once and never recreated until a full reset.
(defonce installed-stubs (atom {}))

(declare compile-module! define-proc! reemit-all-bodies! reset-module! set-build-mode!
         install-globals!)

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
  (if (= @build-mode :reloadable)
    (reset-module!)
    (reset! module-dirty true))
  nil)

(defn current-optimization-level
  "The LLVM IR optimization level used for the next module build."
  []
  @optimization-level)

;;; ---------------------------------------------------------------------------
;;; Panama integration
;;; ---------------------------------------------------------------------------

(defn value-layout
  [type]
  (case type
    :boolean ValueLayout/JAVA_BOOLEAN
    :i64 ValueLayout/JAVA_LONG
    :i32 ValueLayout/JAVA_INT
    :f64 ValueLayout/JAVA_DOUBLE
    :f32 ValueLayout/JAVA_FLOAT
    :ptr ValueLayout/ADDRESS
    (throw (ex-info (str "Unsupported clodin type: " type) {:type type}))))

(deftest test-value-layout
  (is (= ValueLayout/JAVA_BOOLEAN (value-layout :boolean)))
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
    :boolean (cond
               (instance? Boolean value) value
               (integer? value) (not (zero? value))
               :else (throw (ex-info (str "Expected a boolean or integer for :boolean, got "
                                          (some-> value class .getName))
                                      {:type type :value value})))
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
;;; :namespace is the namespace the procedure is defined in, which unqualified
;;; procedure and global references resolve against.
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

(defn- reference-global
  "Returns the module-level global value for a clodin global name. The
  procedure module only declares the symbol; the storage lives in the
  global's own module, so a global is never duplicated into a procedure. The
  symbol carries a revision suffix after a type change, so code emitted
  before the change cannot read the new layout through the old type.

  The declaration carries the storage alignment, because LLVM only lowers an
  atomic access inline when it can prove the access is naturally aligned;
  otherwise it emits a call to __atomic_load/__atomic_store, which the JIT
  cannot resolve."
  [state name]
  (let [{:keys [symbol type]} (globals/entry name)
        name-token (llvm/c-string (:arena state) symbol)
        existing (llvm/named-global (:module state) name-token)]
    (if (llvm/null? existing)
      (llvm/global-declaration (:module state)
                               (llvm/type-for (:context state)
                                               (globals/storage-type type))
                               name-token
                               (globals/storage-size type))
      existing)))

(defn- record-global
  "Records that the procedure being emitted reads or writes the named global,
  so an offline link knows which storage modules to include."
  [state name]
  (update state :globals conj (:symbol (globals/entry name))))

(defn- compile-global-reference
  "Compiles a read of a clodin global as an atomic acquire load. The load is
  atomic because another thread, native or JVM, may write the global, so the
  optimizer must not cache the value across loop iterations. A :boolean
  global is stored as :i32 and converted to i1 here."
  [state name]
  (let [state (record-global state name)
        {:keys [context builder arena]} state
        type (globals/global-type name)
        storage (globals/storage-type type)
        pointer (reference-global state name)
        loaded (llvm/build-atomic-load builder (llvm/type-for context storage)
                                       pointer arena (str name)
                                       (globals/storage-size type))]
    (if (= :boolean type)
      (let [zero (llvm/const-int-of-type context :i32 0)
            flag (llvm/build-icmp builder 'not= loaded zero arena)]
        [state flag :boolean])
      [state loaded type])))

(defn- compile-global-store
  "Compiles (set global value) as an atomic release store. A :boolean value
  is zero extended to the :i32 storage slot."
  [state name value]
  (let [state (record-global state name)
        {:keys [context builder arena]} state
        type (globals/global-type name)
        storage (globals/storage-type type)
        pointer (reference-global state name)
        stored (if (= :boolean type)
                 (llvm/build-zext builder value
                                  (llvm/type-for context storage) arena nil)
                 value)]
    (llvm/build-atomic-store builder stored pointer arena
                             (globals/storage-size type))
    [state nil nil]))

(defn- resolve-global-name
  "The qualified name of the global a source symbol refers to in the given
  namespace, or nil when there is none."
  [referring-namespace name]
  (names/resolve-name referring-namespace name globals/entry))

(defn- compile-local-reference
  "Compiles a symbol reference. A local or parameter shadows a global of the
  same name; otherwise a registered clodin global is read atomically."
  [state environment name]
  (if-let [local (get environment name)]
    (if (= :param (:kind local))
      [state (:value local) (:type local)]
      (let [value (llvm/build-load (:builder state)
                                   (llvm/type-for (:context state) (:type local))
                                   (:value local) (:arena state) nil)]
        [state value (:type local)]))
    (if-let [qualified (resolve-global-name (:namespace state) name)]
      (compile-global-reference state qualified)
      (throw (ex-info (str "Unknown symbol: " name
                           " (not a local, and not a global of "
                           (:namespace state) " or " names/core-namespace ")")
                      {:symbol name
                       :namespace (:namespace state)})))))

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

(def ^:dynamic *symbol-style*
  "How native procedure and global symbols are named. :qualified keeps the
  namespace in the symbol, which a JIT build needs so two namespaces can
  define the same short name. An offline build binds :plain, because the
  system linker exports the plain names and the entry point has to be exactly
  main."
  :qualified)

(defn- procedure-symbol
  "The native symbol for a procedure entry in the current symbol style."
  [entry]
  (names/symbol-name (:namespace entry) (:name entry) *symbol-style*))

(def ^:dynamic *call-target*
  "How a call site names its callee: :stub in reloadable mode and :public for
  monolithic builds and AOT output."
  :stub)

(defn- call-target-symbol
  "The symbol a call site should reference for the given procedure. In
  monolithic and offline builds that is the procedure's own symbol; in
  reloadable mode it is the stub, which every caller shares."
  [procedure]
  (if (= *call-target* :public)
    (procedure-symbol procedure)
    (or (:stub-symbol procedure) (procedure-symbol procedure))))

(defn- procedure-signature
  "The ABI signature of a procedure registry entry."
  [entry]
  {:param-types (vec (:param-types entry))
   :return-type (:return-type entry)})

(defn- void-declared?
  "True when a procedure entry declared :void as its return type. The registry
  keeps :void in :explicit-return-type and nil in :return-type, so the ABI is
  void while the declaration is still visible here and to settle-signatures!."
  [entry]
  (= :void (:explicit-return-type entry)))

(defn- next-body-symbol
  "A fresh body symbol for name. Body symbols are never reused."
  [name]
  (str name "$" (swap! body-counter inc)))

(defn- emit-procedure-call
  "Emits a call to an already resolved procedure with already compiled
  arguments: checks the signature, records the callee for AOT selection and
  re-emission, and returns [state value type].

  compile-procedure-call compiles the argument forms first and delegates here,
  and the print lowering calls it directly because its arguments are already
  compiled."
  [state qualified-name argument-values argument-types]
  (let [procedure (get @procedure-registry qualified-name)]
    (when-not procedure
      (throw (ex-info (str "Unknown clodin procedure: " qualified-name)
                      {:procedure qualified-name})))
    (when-not (= (count argument-values) (count (:param-types procedure)))
      (throw (ex-info (str "Procedure " qualified-name " expects "
                           (count (:param-types procedure))
                           " arguments, got " (count argument-values))
                      {:procedure qualified-name})))
    (when-not (= (vec argument-types) (vec (:param-types procedure)))
      (throw (ex-info (str "Argument types " (vec argument-types)
                           " do not match parameters " (:param-types procedure)
                           " for procedure " qualified-name)
                      {:procedure qualified-name
                       :argument-types (vec argument-types)})))
    (let [{:keys [module context builder arena]} state
          target-name (call-target-symbol procedure)
          name-token (llvm/c-string arena target-name)
          callee-type (llvm/function-type context arena (:return-type procedure)
                                          (:param-types procedure))
          callee (llvm/get-or-add-function module name-token callee-type)
          result (llvm/build-call builder callee-type callee argument-values arena)
          state (assoc state :callees
                       (assoc (:callees state) qualified-name
                              {:param-types (vec (:param-types procedure))
                               :return-type (:return-type procedure)}))]
      (if (:return-type procedure)
        [state result (:return-type procedure)]
        [state nil nil]))))

(defn- compile-procedure-call
  "Compiles a call to another clodin procedure by its qualified name. The call
  is emitted as a direct llvm call inside the JIT module, so the jvm is not
  involved when native procedures call each other."
  [state environment qualified-name argument-forms]
  (let [[state argument-values argument-types]
        (compile-operands state environment argument-forms)]
    (emit-procedure-call state qualified-name argument-values argument-types)))

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
  "Dispatches a call to a clodin procedure or to an external C function.

  A procedure reference resolves like a global reference: the referring
  namespace first, then clodin.core, or the qualified name as written. An
  external C function is a bare name with no namespace, so it is considered
  only when no procedure matches."
  [state environment procedure-symbol argument-forms]
  (let [qualified (names/resolve-name (:namespace state) procedure-symbol
                                      #(get @procedure-registry %))]
    (cond
      qualified
      (compile-procedure-call state environment qualified argument-forms)

      (contains? @external-registry (str procedure-symbol))
      (compile-external-call state environment procedure-symbol
                             (get @external-registry (str procedure-symbol))
                             argument-forms)

      :else
      (throw (ex-info (str "Unknown clodin procedure: " procedure-symbol
                           " (not in " (:namespace state) ", not in "
                           names/core-namespace ", and not an external function)")
                      {:procedure procedure-symbol
                       :namespace (:namespace state)})))))

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
  "Compiles (to-float x), a cast of an integer or a 32 bit float value to a
  64 bit float."
  [state environment expression]
  (when-not (= 2 (count expression))
    (throw (ex-info "to-float expects exactly one operand" {:expression expression})))
  (let [[state value type] (compile-expression state environment (second expression))]
    (case type
      (:i64 :i32)
      (let [result (llvm/build-sitofp (:builder state) value
                                      (llvm/type-for (:context state) :f64)
                                      (:arena state) nil)]
        [state result :f64])
      :f32
      (let [result (llvm/build-fpext (:builder state) value
                                     (llvm/type-for (:context state) :f64)
                                     (:arena state) nil)]
        [state result :f64])
      (throw (ex-info (str "to-float expects an integer or an :f32, got " type)
                      {:type type})))))

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
  "Compiles (to-i32 x), truncating a 64 bit integer to 32 bits or zero
  extending a :boolean."
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
      :boolean (let [result (llvm/build-zext (:builder state) value
                                             (llvm/type-for (:context state) :i32)
                                             (:arena state) nil)]
                 [state result :i32])
      (throw (ex-info (str "to-i32 expects an integer, got " type)
                      {:type type})))))

(defn- compile-to-i64
  "Compiles (to-i64 x), sign extending a 32 bit integer to 64 bits or zero
  extending a :boolean."
  [state environment expression]
  (when-not (= 2 (count expression))
    (throw (ex-info "to-i64 expects exactly one operand" {:expression expression})))
  (let [[state value type] (compile-expression state environment (second expression))]
    (case type
      :i64 [state value :i64]
      :i32 (let [result (llvm/build-sext (:builder state) value
                                         (llvm/type-for (:context state) :i64)
                                         (:arena state) nil)]
             [state result :i64])
      :boolean (let [result (llvm/build-zext (:builder state) value
                                             (llvm/type-for (:context state) :i64)
                                             (:arena state) nil)]
                 [state result :i64])
      (throw (ex-info (str "to-i64 expects an integer, got " type)
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
    :boolean 1
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

(def ^:private maximum-stack-alignment
  "The largest stack-allocate alignment in bytes. LLVM allows up to 2^32, but
  LLVMSetAlignment takes the alignment as a 32 bit unsigned integer and no real
  stack block needs more than this."
  65536)

(defn- stack-allocate-literal
  "Returns one compile time argument of stack-allocate as a long. The block is
  a single alloca, so neither its size nor its alignment may depend on a runtime
  value, and both must fit in a long."
  [argument-name form]
  (if (and (integer? form) (<= 0 form Long/MAX_VALUE))
    (long form)
    (throw (ex-info (str "stack-allocate expects a literal non-negative integer "
                         argument-name ", got " (pr-str form))
                    {:argument argument-name :value form}))))

(deftest test-stack-allocate-literal
  (testing "integer literals are accepted"
    (is (= 32 (stack-allocate-literal "byte-count" 32)))
    (is (= 0 (stack-allocate-literal "alignment" 0))))
  (testing "a runtime value or a non-integer is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"literal non-negative integer byte-count"
                          (stack-allocate-literal "byte-count" 'byte-count)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (stack-allocate-literal "byte-count" -8)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (stack-allocate-literal "alignment" 1.5)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (stack-allocate-literal "byte-count" 18446744073709551616N)))))

(defn- power-of-two?
  "True when value is a positive power of two, which is what LLVM accepts as an
  alloca alignment."
  [value]
  (and (pos? value) (zero? (bit-and value (dec value)))))

(deftest test-power-of-two
  (is (every? power-of-two? [1 2 4 8 16 32 1024]))
  (is (not-any? power-of-two? [0 3 6 12 24 -16])))

(defn- compile-stack-allocate
  "Compiles (stack-allocate byte-count alignment), which reserves an
  uninitialised block of byte-count bytes aligned to alignment bytes on the
  stack and yields a :ptr to it. Both arguments are compile time integer
  literals and alignment is a power of two.

  The block is hoisted to the entry block, so one inside a while is the same
  bytes on every iteration instead of growing the stack, and a loop has to
  initialise what it reads. Each call of the procedure gets its own block, so
  the pointer is per-call scratch that dies with the call and must not be
  returned or stored into longer lived memory."
  [state _environment expression]
  (when-not (= 3 (count expression))
    (throw (ex-info "stack-allocate expects (stack-allocate byte-count alignment)"
                    {:expression expression})))
  (let [[byte-count-form alignment-form] (rest expression)
        byte-count (stack-allocate-literal "byte-count" byte-count-form)
        alignment (stack-allocate-literal "alignment" alignment-form)]
    (when (zero? byte-count)
      (throw (ex-info "stack-allocate needs at least one byte"
                      {:byte-count byte-count})))
    (when-not (power-of-two? alignment)
      (throw (ex-info (str "stack-allocate alignment must be a power of two, got "
                           alignment)
                      {:alignment alignment})))
    (when (> alignment maximum-stack-alignment)
      (throw (ex-info (str "stack-allocate alignment must be at most "
                           maximum-stack-alignment ", got " alignment)
                      {:alignment alignment})))
    (let [context (:context state)
          block (llvm/hoisted-alloca
                 (:builder state) context (:arena state)
                 (:entry-block state) (:current-block state)
                 (llvm/array-type (llvm/i8-type context) byte-count)
                 alignment)]
      [state block :ptr])))

(defn- type-name
  "A readable name for a clodin type. nil means the form produced no value."
  [type]
  (or type "no value"))

(def ^:private runtime-namespace
  "The namespace that holds the native print helpers the compiler lowers print
  and println to. It is written in clodin itself, so it is loaded lazily on
  the first procedure definition rather than required at the top of this
  namespace, which would be a cycle."
  "clodin.runtime")

(defonce runtime-loaded
  (atom false))

(defn- require-runtime!
  "Loads clodin.runtime once. The flag is set before the require so the
  runtime's own defproc forms, which run while it loads, do not recurse."
  []
  (when-not @runtime-loaded
    (reset! runtime-loaded true)
    (require (symbol runtime-namespace)))
  nil)

(defonce core-loaded
  (atom false))

(defn- require-core!
  "Loads clodin.core once. The flag is set before the require so the core's
  own defproc forms, which run while it loads, do not recurse."
  []
  (when-not @core-loaded
    (reset! core-loaded true)
    (require (symbol names/core-namespace)))
  nil)

(def ^:private scalar-types
  "The types a pointer-call signature may name. Aggregate types do not exist
  yet, and :void is only legal as a return type."
  #{:boolean :i64 :i32 :f64 :f32 :ptr})

(defn- pointer-call-signature
  "Reads the literal signature of a pointer-call: a vector of parameter types
  and a return type, where :void (or nil) means the call produces no value.

  The signature is written at the call site because a :ptr carries no type.
  LLVM trusts the function type it is given, so a mismatch does not fail at
  build time, it silently reinterprets values; this checks everything the
  written form can be checked against."
  [param-type-forms return-type-form]
  (when-not (vector? param-type-forms)
    (throw (ex-info "pointer-call expects a literal vector of parameter types"
                    {:param-types param-type-forms})))
  (doseq [param-type param-type-forms]
    (when-not (contains? scalar-types param-type)
      (throw (ex-info (str "pointer-call parameter types are "
                           ":boolean :i64 :i32 :f64 :f32 :ptr, got "
                           (pr-str param-type))
                      {:param-type param-type}))))
  (let [void-return? (or (nil? return-type-form) (= :void return-type-form))]
    (when-not (or void-return? (contains? scalar-types return-type-form))
      (throw (ex-info (str "pointer-call return type is "
                           ":void :boolean :i64 :i32 :f64 :f32 :ptr, got "
                           (pr-str return-type-form))
                      {:return-type return-type-form})))
    {:param-types (vec param-type-forms)
     :return-type (when-not void-return? return-type-form)}))

(deftest test-pointer-call-signature
  (is (= {:param-types [:ptr :i64] :return-type :i32}
         (pointer-call-signature [:ptr :i64] :i32)))
  (is (= {:param-types [] :return-type nil}
         (pointer-call-signature [] :void)))
  (is (= {:param-types [] :return-type nil}
         (pointer-call-signature [] nil)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal vector"
                        (pointer-call-signature '(:ptr) :i64)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"parameter types are"
                        (pointer-call-signature [:void] :i64)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"return type is"
                        (pointer-call-signature [] :i8))))

(defn- compile-pointer-call
  "Compiles (pointer-call pointer [param-types] return-type arguments...), an
  indirect call through a :ptr that holds a function address, so clodin source
  can reach a JVM upcall stub or a C function address.

  There is no null guard: a null pointer jumps to address zero and takes the
  process down. A caller whose pointer may be absent tests it first with
  is-null, which is what the print runtime's writer pointer does."
  [state environment expression]
  (when (< (count expression) 4)
    (throw (ex-info (str "pointer-call expects (pointer-call pointer"
                         " [param-types] return-type arguments...)")
                    {:expression expression})))
  (let [[pointer-form param-type-forms return-type-form & argument-forms]
        (rest expression)
        {:keys [param-types return-type]}
        (pointer-call-signature param-type-forms return-type-form)
        [state pointer-value pointer-type]
        (compile-expression state environment pointer-form)]
    (when-not (= :ptr pointer-type)
      (throw (ex-info (str "pointer-call expects a :ptr function address, got "
                           (type-name pointer-type))
                      {:type pointer-type})))
    (when-not (= (count argument-forms) (count param-types))
      (throw (ex-info (str "pointer-call signature " (vec param-types) " takes "
                           (count param-types) " arguments, got "
                           (count argument-forms))
                      {:param-types param-types
                       :argument-count (count argument-forms)})))
    (let [[state argument-values argument-types]
          (compile-operands state environment argument-forms)]
      (when-not (= argument-types param-types)
        (throw (ex-info (str "Argument types " argument-types
                             " do not match pointer-call parameters " param-types)
                        {:argument-types argument-types
                         :param-types param-types})))
      (let [{:keys [context builder arena]} state
            function-type (llvm/function-type context arena return-type param-types)
            result (llvm/build-call builder function-type pointer-value
                                    argument-values arena)]
        (if return-type
          [state result return-type]
          [state nil nil])))))

(defn- compile-let
  "Compiles (let [name type value ...] body...). Every binding gets its
  own stack slot, so a later binding may shadow an earlier one and the
  initial value of a binding is evaluated in the environment of the
  bindings before it.

  value-needed? false compiles the body in statement position, which a let in
  statement position forwards to its own last body form."
  [state environment expression value-needed?]
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
                                             " but its value is " (type-name value-type))
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
      (let [inner (compile-body state environment body-forms value-needed?)]
        [inner (:result-value inner) (:return-type inner)]))))

(defn- compile-set
  "Compiles (set name value). A local or parameter is written to its stack
  slot; a global is written through an atomic release store. A set does not
  produce a value. Parameters are raw values and cannot be set."
  [state environment expression]
  (when-not (= 3 (count expression))
    (throw (ex-info "set expects (set name value)" {:expression expression})))
  (let [[name value-form] (rest expression)]
    (when-not (symbol? name)
      (throw (ex-info (str "set expects a local or global name, got " name)
                      {:name name})))
    (if-let [local (get environment name)]
      (do
        (when (= :param (:kind local))
          (throw (ex-info (str "Cannot set the function parameter " name
                               "; parameters are raw llvm values, not stack slots")
                          {:name name})))
        (let [[state value value-type] (compile-expression state environment value-form)]
          (when-not (= (:type local) value-type)
            (throw (ex-info (str "Cannot set " name " of type " (:type local)
                                 " to a value of type " (type-name value-type))
                            {:name name :type (:type local) :value-type value-type})))
          (llvm/build-store (:builder state) value (:value local))
          [state nil nil]))
      (if-let [qualified (resolve-global-name (:namespace state) name)]
        (let [global-type (globals/global-type qualified)
              [state value value-type] (compile-expression state environment value-form)]
          (when-not (= global-type value-type)
            (throw (ex-info (str "Cannot set global " name " of type " global-type
                                 " to a value of type " (type-name value-type))
                            {:name name :type global-type :value-type value-type})))
          (compile-global-store state qualified value))
        (throw (ex-info (str "Cannot set unknown local or global: " name)
                        {:symbol name}))))))

(defn- call-print-helper
  "Calls one of the print helpers in clodin.runtime with already compiled
  arguments. Going through emit-procedure-call gives a print the same
  signature check, stub target and callee record as any other call, so an
  offline build links the runtime because it is in the call graph."
  [state helper-name argument-values argument-types]
  (first (emit-procedure-call state
                              (names/qualified-name runtime-namespace helper-name)
                              argument-values argument-types)))

(defn- compile-print-operand
  "Lowers one print operand according to its clodin type. A string literal is
  written with its known byte length; a :ptr is treated as a null terminated C
  string."
  [state environment operand]
  (if (string? operand)
    (let [[state value _type] (compile-expression state environment operand)
          length (alength (.getBytes ^String operand
                                     java.nio.charset.StandardCharsets/UTF_8))]
      (call-print-helper state "print-bytes"
                         [value (llvm/const-int-of-type (:context state) :i64 length)]
                         [:ptr :i64]))
    (let [[state value type] (compile-expression state environment operand)]
      (case type
        :i64 (call-print-helper state "print-i64" [value] [:i64])
        :i32 (call-print-helper state "print-i32" [value] [:i32])
        :f64 (call-print-helper state "print-f64" [value] [:f64])
        :f32 (call-print-helper state "print-f32" [value] [:f32])
        :boolean (call-print-helper state "print-bool" [value] [:boolean])
        :ptr (call-print-helper state "print-cstr" [value] [:ptr])
        (throw (ex-info (str "Cannot print a value of type " type)
                        {:type type :operand operand}))))))

(defn- compile-print
  "Compiles (print ...) and (println ...). Each operand is formatted by the
  native print runtime, which writes to the calling thread's *out* through a
  JVM upcall, or to file descriptor 1 in an offline build. A print does not
  produce a value."
  [state environment expression println?]
  (let [state (reduce (fn [state operand]
                        (compile-print-operand state environment operand))
                      state
                      (rest expression))
        state (if println?
                (call-print-helper state "print-newline" [] [])
                state)]
    [state nil nil]))

(defn- compile-comparison
  "Compiles a binary comparison into an llvm icmp or fcmp instruction.
  The result is a :boolean."
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
      [state result :boolean])))

(defn- compile-boolean-condition
  "Compiles a branching form's condition and checks that it is a :boolean."
  [state environment condition-form form-name]
  (let [[state value type] (compile-expression state environment condition-form)]
    (when-not (= :boolean type)
      (throw (ex-info (str form-name " condition must be a :boolean, got " type)
                      {:form form-name :type type})))
    [state value]))

(defn- arm-diverges?
  "True when a branched arm ended in a terminator, such as an early return, so
  it never falls through to the block after the branch."
  [arm-state]
  (llvm/block-terminated? (:current-block arm-state)))

(defn- merge-branch-state
  "The emitter state after a branch, given the state before it and the states
  the arms were compiled into.

  The arms are compiled as separate continuations, so the bookkeeping that
  accumulates while emitting has to be unioned instead of taken from one arm.
  The callees and globals a body references decide which namespaces and storage
  modules an AOT link includes, so a callee called only inside an arm would
  otherwise be dropped. The string counter is advanced to the highest value the
  arms reached, so a literal after the branch does not reuse an arm's index.
  Two arms can still mint the same index, which is harmless: the names are
  private constants and LLVM renames the second one."
  [state arm-states]
  (reduce (fn [state arm-state]
            (-> state
                (update :callees merge (:callees arm-state))
                (update :globals into (:globals arm-state))
                (update :string-counter max (:string-counter arm-state))))
          state
          arm-states))

(defn- branch-result-type
  "The type a branched form produces, given the two arm types and whether each
  arm diverges. A diverging arm is compatible with any type because it never
  reaches the join block. Two value arms must agree, and mixing a value arm
  with a statement arm is an error."
  [form-name then-type else-type then-diverges? else-diverges?]
  (cond
    (and then-diverges? else-diverges?) nil
    then-diverges? else-type
    else-diverges? then-type
    (= then-type else-type) then-type
    :else
    (throw (ex-info (str form-name " arms must both produce a value of the"
                         " same type, or both be statements; got "
                         (or then-type "no value") " and "
                         (or else-type "no value"))
                    {:form form-name
                     :then-type then-type
                     :else-type else-type}))))

(defn- compile-if
  "Compiles (if condition then else) as a real branch, with Clojure's
  semantics: only the taken arm runs, so either arm may have side effects.

  In value position both arms must produce a value of the same type, which the
  form produces, merged with a phi node in the join block. Mixing a value arm
  with a statement arm is an error.

  In statement position (value-needed? false) both arms are compiled as
  statements, so their values are discarded and the arms need not agree in
  type: each arm is run for its side effects, and the return value that is
  useful in one case need not be the same as in another. No phi is built and
  the form produces no value.

  An arm that ends in a terminator, such as an early return, never reaches the
  join block. It contributes no incoming phi value, and its type is compatible
  with the other arm's. If both arms terminate, the whole form diverges and the
  join block is marked unreachable."
  [state environment expression value-needed?]
  (when-not (= 4 (count expression))
    (throw (ex-info "if expects (if condition then else)" {:expression expression})))
  (let [[condition-form then-form else-form] (rest expression)
        [state condition-value]
        (compile-boolean-condition state environment condition-form "if")
        {:keys [context function builder arena]} state
        then-block (llvm/new-block context arena "if.then")
        else-block (llvm/new-block context arena "if.else")
        join-block (llvm/new-block context arena "if.join")]
    (llvm/append-block function then-block)
    (llvm/append-block function else-block)
    (llvm/append-block function join-block)
    (llvm/build-cond-br builder condition-value then-block else-block)
    (llvm/position-at-end builder then-block)
    (let [compile-arm (if value-needed? compile-expression compile-statement)
          [then-state then-value then-type]
          (compile-arm (assoc state :current-block then-block) environment then-form)
          _ (llvm/position-at-end builder else-block)
          [else-state else-value else-type]
          (compile-arm (assoc state :current-block else-block) environment else-form)
          then-diverges? (arm-diverges? then-state)
          else-diverges? (arm-diverges? else-state)
          ;; A statement if discards both arm values, so their types are
          ;; irrelevant and there is nothing to merge.
          result-type (when value-needed?
                        (branch-result-type "if" then-type else-type
                                            then-diverges? else-diverges?))
          join-state (-> (merge-branch-state state [then-state else-state])
                         (assoc :current-block join-block
                                :terminated? false))]
      (cond
        (and then-diverges? else-diverges?)
        (do
          ;; Neither arm reaches the join block. A block still has to end in a
          ;; terminator, so mark the join unreachable and make the form
          ;; diverge. This is the shape (if c (return 1) (return 2)).
          (llvm/position-at-end builder join-block)
          (llvm/build-unreachable builder)
          [(assoc join-state :terminated? true :result-value nil :return-type nil)
           nil nil])

        else-diverges?
        (do
          ;; Only the then arm falls through, so the join has one predecessor
          ;; and its value can be used directly: dominance already guarantees
          ;; this arm ran.
          (llvm/position-at-end builder (:current-block then-state))
          (llvm/build-br builder join-block)
          (llvm/position-at-end builder join-block)
          [join-state then-value result-type])

        then-diverges?
        (do
          (llvm/position-at-end builder (:current-block else-state))
          (llvm/build-br builder join-block)
          (llvm/position-at-end builder join-block)
          [join-state else-value result-type])

        :else
        (do
          ;; Branch from each arm's own final block: a nested branching form
          ;; inside an arm may have moved the builder past its first block.
          (llvm/position-at-end builder (:current-block else-state))
          (llvm/build-br builder join-block)
          (llvm/position-at-end builder (:current-block then-state))
          (llvm/build-br builder join-block)
          (llvm/position-at-end builder join-block)
          (if result-type
            (let [phi (llvm/build-phi builder (llvm/type-for context result-type) arena)]
              (llvm/add-incoming phi arena [then-value else-value]
                                 [(:current-block then-state) (:current-block else-state)])
              [join-state phi result-type])
            [join-state nil nil]))))))

(defn- compile-when
  "Compiles (when condition body...) as a real branch with no else arm: the
  body runs only when the condition is true, and only then.

  Unlike Clojure's when, the form produces no value. Clodin has no nil value to
  return when the condition is false, so use if when a value is needed."
  [state environment expression]
  (let [condition-form (second expression)
        body-forms (drop 2 expression)]
    ;; Check arity, not truthiness: the literal condition false is valid.
    (when (< (count expression) 2)
      (throw (ex-info "when expects a condition" {:expression expression})))
    (let [[state condition-value]
          (compile-boolean-condition state environment condition-form "when")
          {:keys [context function builder arena]} state
          body-block (llvm/new-block context arena "when.body")
          join-block (llvm/new-block context arena "when.end")]
      (llvm/append-block function body-block)
      (llvm/append-block function join-block)
      (llvm/build-cond-br builder condition-value body-block join-block)
      (llvm/position-at-end builder body-block)
      (let [body-state (compile-body (assoc state :current-block body-block)
                                     environment body-forms false)]
        (when-not (arm-diverges? body-state)
          (llvm/position-at-end builder (:current-block body-state))
          (llvm/build-br builder join-block))
        (llvm/position-at-end builder join-block)
        [(-> (merge-branch-state state [body-state])
             (assoc :current-block join-block
                    :terminated? false
                    :result-value nil
                    :return-type nil))
         nil nil]))))

(defn- compile-while
  "Compiles (while condition body...) into a condition block, a body block
  and an end block. The condition must be a :boolean."
  [state environment expression]
  (let [condition-form (second expression)
        body-forms (drop 2 expression)]
    ;; Check arity, not truthiness: the literal condition false is valid.
    (when (< (count expression) 2)
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
        (when-not (= :boolean condition-type)
          (throw (ex-info (str "while condition must be a comparison, got " condition-type)
                          {:type condition-type})))
        (llvm/build-cond-br builder condition-value body-block end-block)
        (llvm/position-at-end builder body-block)
        (let [state (assoc state :current-block body-block)
              state (compile-body state environment body-forms false)]
          (llvm/build-br builder condition-block)
          (llvm/position-at-end builder end-block)
          (let [state (assoc state :current-block end-block
                             :result-value nil :return-type nil)]
            [state nil nil]))))))

(defn- compile-expression
  [state environment expression]
  (cond
    (integer? expression) [state (llvm/const-int (:context state) expression) :i64]
    (float? expression) [state (llvm/const-real (:context state) :f64 expression) :f64]
    (boolean? expression) [state (llvm/const-int-of-type (:context state) :boolean
                                                         (if expression 1 0))
                           :boolean]
    (string? expression) (compile-string-literal state expression)
    (symbol? expression) (compile-local-reference state environment expression)
    (seq? expression)
    (let [operator (first expression)
          {:keys [context module builder arena] :as state} state]
      (cond
        (#{'+ '- '* '/} operator) (compile-arithmetic state environment operator (rest expression))
        (#{'< '<= '> '>= '=} operator) (compile-comparison state environment operator (rest expression))
        (= operator 'to-float) (compile-to-float state environment expression)
        (= operator 'to-f32) (compile-to-f32 state environment expression)
        (= operator 'to-i32) (compile-to-i32 state environment expression)
        (= operator 'to-i64) (compile-to-i64 state environment expression)
        (= operator 'sqrt) (compile-sqrt state environment expression)
        (= operator 'abs) (compile-abs state environment expression)
        (#{'min 'max} operator) (compile-min-or-max state environment operator (rest expression))
        (= operator 'load) (compile-load state environment expression)
        (= operator 'store) (compile-store state environment expression)
        (= operator 'pointer-add) (compile-pointer-add state environment expression)
        (= operator 'buffer-get) (compile-buffer-get state environment expression)
        (= operator 'buffer-set) (compile-buffer-set state environment expression)
        (= operator 'to-pointer) (compile-to-pointer state environment expression)
        (= operator 'pointer-call) (compile-pointer-call state environment expression)
        (= operator 'stack-allocate) (compile-stack-allocate state environment expression)
        (= operator 'let) (compile-let state environment expression true)
        (= operator 'set) (compile-set state environment expression)
        (= operator 'print) (compile-print state environment expression false)
        (= operator 'println) (compile-print state environment expression true)
        (= operator 'if) (compile-if state environment expression true)
        (= operator 'when) (compile-when state environment expression)
        (= operator 'while) (compile-while state environment expression)
        :else (compile-call state environment operator (rest expression))))
    :else (throw (ex-info (str "Unsupported clodin expression: " expression)
                          {:expression expression}))))

(defn- compile-body
  "Compiles the body expressions in order and returns the emitter state
  extended with the result value and return type of the last expression.
  A body form that produces no value, such as set or while, leaves the
  result value nil.

  Every form but the last is compiled in statement position, because its value
  is discarded. value-needed? false does the same for the last form, for a body
  whose value is discarded too: a when or while body, or the body of a
  procedure declared :void."
  ([state environment body-forms]
   (compile-body state environment body-forms true))
  ([state environment body-forms value-needed?]
   (let [last-index (dec (count body-forms))]
     (reduce (fn [state index]
               ;; Once a form has terminated the block, later forms are
               ;; unreachable and must not append to it. Early return relies on
               ;; this; a branch whose arms both terminate also sets the flag.
               (if (:terminated? state)
                 state
                 (let [body-form (nth body-forms index)
                       [state value type]
                       (if (and value-needed? (= index last-index))
                         (compile-expression state environment body-form)
                         (compile-statement state environment body-form))]
                   (assoc state :result-value value :return-type type))))
             (assoc state :result-value nil :return-type nil :terminated? false)
             (range (count body-forms))))))

(defn- compile-statement
  "Compiles a form for its side effects only: any value it produces is
  discarded and the form may produce none at all.

  Statement position propagates into the forms that forward their value, so an
  if compiles both arms as statements and a let compiles its body the same way.
  Every other form is compiled in value position and its value dropped, which
  keeps the rule in one place: a form's subexpressions are always value
  position unless the form itself is a branching or binding form that discards
  its result."
  [state environment expression]
  (let [operator (when (seq? expression) (first expression))]
    (cond
      (= operator 'if) (compile-if state environment expression false)
      (= operator 'let) (compile-let state environment expression false)
      :else (let [[state _value _type] (compile-expression state environment expression)]
              [state nil nil]))))

(defn- expression-type
  "Returns the clodin type a form produces, or nil when there is none."
  [environment expression]
  (cond
    (integer? expression) :i64
    (float? expression) :f64
    (boolean? expression) :boolean
    (string? expression) :ptr
    (symbol? expression) (or (:type (get environment expression))
                              (some-> (resolve-global-name (get environment ::namespace)
                                                           expression)
                                      globals/global-type))
    (seq? expression)
    (let [operator (first expression)]
      (when (symbol? operator)
        (cond
          (#{'+ '- '* '/} operator) (expression-type environment (second expression))
          (#{'< '<= '> '>= '=} operator) :boolean
          (= operator 'to-float) :f64
          (= operator 'to-f32) :f32
          (= operator 'to-i32) :i32
          (= operator 'to-i64) :i64
          (#{'sqrt 'abs} operator) (expression-type environment (second expression))
          (#{'min 'max} operator) (expression-type environment (second expression))
          (= operator 'load) (second expression)
          (= operator 'store) nil
          (= operator 'pointer-add) :ptr
          (= operator 'buffer-get) (nth expression 2)
          (= operator 'buffer-set) nil
          (= operator 'to-pointer) :ptr
          (= operator 'pointer-call)
          ;; A malformed form falls through as no value so that compilation
          ;; reports the arity error rather than this reader.
          (when (>= (count expression) 4)
            (:return-type (pointer-call-signature (nth expression 2)
                                                  (nth expression 3))))
          (= operator 'stack-allocate) :ptr
          (= operator 'let)
          (let [[_ bindings & inner-body] expression
                environment (reduce (fn [env [name declared-type _]]
                                      (assoc env name {:type declared-type}))
                                    environment
                                    (partition 3 bindings))]
            (expression-type environment (last inner-body)))
          (= operator 'set) nil
          (#{'print 'println} operator) nil
          (= operator 'if) (expression-type environment (nth expression 2))
          (= operator 'when) nil
          (= operator 'while) nil
          :else (let [referring-namespace (get environment ::namespace)
                      qualified (names/resolve-name
                                 referring-namespace operator
                                 #(get @procedure-registry %))
                      external (get @external-registry (str operator))]
                  (if qualified
                    (:return-type (get @procedure-registry qualified))
                    (:return-type external))))))
    :else nil))

;;; Procedure -> bitcode
;;; ---------------------------------------------------------------------------

(defn- build-procedure!
  "Builds the LLVM IR for a single procedure through the C API and returns
  {:bitcode byte[] :return-type ... :result-value ... :callees {...}}.
  name is the public procedure name used in error messages and string
  symbol names; emitted-name is the function and module symbol. In
  reloadable mode emitted-name is foo$N, in monolithic mode it is foo.

  statement-body? means the procedure declared :void, so its body is compiled
  in statement position and whatever the last form produces is discarded."
  [referring-namespace name emitted-name param-names param-types return-type
   body-forms statement-body?]
  (let [arena (Arena/ofConfined)
        context (llvm/new-context)
        module (llvm/new-module context (str emitted-name))
        function-type (llvm/function-type context arena return-type param-types)]
    (try
      (let [function (llvm/add-function module (llvm/c-string arena (str emitted-name))
                                       function-type)
            builder (llvm/new-builder context)
            entry-block (llvm/append-entry-block function builder context arena "entry")
            state {:arena arena :context context :module module :builder builder
                   :function function :entry-block entry-block :current-block entry-block
                   :procedure-name emitted-name :string-counter 0
                   :namespace referring-namespace
                   :callees {} :globals #{}}
            environment (into {} (map (fn [param-name param-type index]
                                        [param-name {:type param-type
                                                     :value (llvm/get-param function index)
                                                     :kind :param}])
                                      param-names param-types (range)))
            state (compile-body state environment body-forms (not statement-body?))
            result-value (:result-value state)
            effective-return-type (or return-type (:return-type state))]
        (when (and (not (:terminated? state))
                   effective-return-type return-type result-value
                   (not= return-type (:return-type state)))
          (throw (ex-info (str "Procedure " name " declares return type "
                               return-type " but its body produces "
                               (:return-type state))
                          {:procedure name
                           :return-type return-type
                           :body-type (:return-type state)
                           :ast body-forms})))
        ;; A terminated body already ended every path in a terminator, so there
        ;; is nothing left to return from.
        (when-not (:terminated? state)
          (if effective-return-type
            (do (when-not result-value
                  (throw (ex-info (str "Procedure " name " has no value to return")
                                  {:procedure name :return-type effective-return-type})))
                (llvm/build-ret builder result-value))
            (llvm/build-ret-void builder)))
        (llvm/dispose-builder builder)
        (llvm/verify module arena)
        (let [bitcode (llvm/write-bitcode module)]
          {:bitcode bitcode
           :return-type effective-return-type
           :result-value result-value
           :callees (:callees state)
           :globals (:globals state)}))
      (finally
        (llvm/dispose-module module)
        (llvm/dispose-context context)
        (.close arena)))))

;;; ---------------------------------------------------------------------------
;;; Reloadable mode: stubs, body modules, installation
;;;
;;; A reloadable procedure is a stable stub module (a pointer cell plus a
;;; thunk that loads it atomically) and one body module per compilation.
;;; Redefinition adds a body module and calls the stub's setter, so callers
;;; keep calling the same symbol and running native loops see the new body.
;;; ---------------------------------------------------------------------------

(defn- install-writer!
  "Points clodin.runtime/clodin-write at the JVM upcall stub, so a print from
  native code reaches the calling thread's *out*. Every build has to do this:
  a monolithic rebuild reinitialises global storage from the source initial
  value, and a reset drops it. Offline builds never call it, so the writer
  stays null and write-to-out falls back to write(1, ...).

  The global's storage must already be recorded, which is why the callers run
  this after installing or linking it: writing through a global with no
  address would ask ensure-storage! for one, and in monolithic mode that hook
  compiles the module again while the current build is still in progress."
  [jit]
  (let [qualified (names/qualified-name runtime-namespace "clodin-write")]
    (when-let [entry (globals/entry qualified)]
      ;; Both checks matter: with no recorded address, writing would ask
      ;; ensure-storage! for one and compile the module again.
      (when (and (:address entry) (= jit (:jit entry)))
        (globals/write-value! qualified (print/writer-address)))))
  nil)

(defn- ensure-jit!
  "Returns the long-lived LLJIT, creating it on first use. Reloadable mode
  keeps one JIT for the session; reset-module! disposes it."
  []
  (or @current-jit
      (locking compile-lock
        (or @current-jit
            (let [arena (Arena/ofConfined)]
              (try
                (llvm/init-targets!)
                (let [jit (llvm/create-jit arena)]
                  (reset! current-jit jit)
                  (install-globals! jit)
                  jit)
                (finally
                  (.close arena))))))))

(defn- global-storage-module
  "Builds a module that defines one global variable per spec, with the
  initial value as its initializer. The module owns the storage, so it is
  added to the JIT exactly once per generation and procedure modules only
  declare the symbol."
  [context specs arena]
  (let [module (llvm/new-module context "globals")]
    (doseq [[symbol type initial-value] specs]
      (let [storage (globals/storage-type type)
            global (llvm/add-global module
                                     (llvm/type-for context storage)
                                     (llvm/c-string arena (str symbol)))]
        (llvm/set-alignment global (globals/storage-size type))
        (llvm/set-initializer
         global
         (globals/constant-value context type initial-value))))
    module))

(defn- record-global-address!
  "Records the address of a global's storage in jit, given its native symbol.
  The storage module (and therefore the symbol) must already be loaded."
  [jit symbol]
  (let [arena (Arena/ofConfined)]
    (try
      (let [address (llvm/lookup-address jit (llvm/c-string arena (str symbol)) arena)]
        (when-not address
          (throw (ex-info (str "JIT has no global storage for " symbol)
                          {:global symbol})))
        (let [name (some (fn [[name entry]]
                           (when (= symbol (:symbol entry)) name))
                         @globals/registry)]
          (globals/install! name symbol address jit)))
      (finally
        (.close arena)))))

(defn- record-global-addresses!
  "Records the addresses of every global declared by the given symbols. Used
  after a monolithic build, where the storage is linked into the procedure
  module itself rather than installed separately."
  [jit symbols]
  (doseq [symbol symbols]
    (record-global-address! jit symbol)))

(defn- install-globals!
  "Adds the storage module for every registered global whose symbol has no
  storage in jit, then records each global's address. Globals that are
  already installed in this JIT generation are left alone, so redefining a
  procedure never reinitialises a global. Returns the set of installed native
  symbols.

  This is the reloadable-mode path, where the JIT is long lived and storage
  needs its own stable module. A monolithic build links the storage into the
  procedure module instead and only records the addresses."
  [jit]
  (locking compile-lock
    (let [already (globals/installed-symbols jit)
          pending (into []
                        (remove (fn [[symbol _type _value]]
                                  (contains? already symbol)))
                        (globals/storage-specs))]
      (when (seq pending)
        (let [arena (Arena/ofConfined)]
          (try
            (let [context (llvm/new-context)
                  module (global-storage-module context pending arena)
                  added? (volatile! false)]
              (try
                (llvm/verify module arena)
                (vreset! added? true)
                (llvm/add-module-to-jit! jit module context)
                ;; On success the JIT owns the module and its context, so
                ;; neither may be disposed here.
                (doseq [[symbol _type _initial-value] pending]
                  (record-global-address! jit symbol))
                (finally
                  (when-not @added?
                    (llvm/dispose-module module)
                    (llvm/dispose-context context)))))
            (finally
              (.close arena))))))
    (install-writer! jit)
    (globals/installed-symbols jit)))

(defn- ensure-global-storage!
  "Makes sure the current JIT generation holds storage for every global. This
  is the lazy hook the JVM side of a global calls before its first access, so
  @running? works before any procedure has been invoked.

  In monolithic mode the whole procedure module and the global storage modules
  share one JIT that is replaced on every rebuild, so the build has to run
  first; installing storage into a throwaway JIT would lose it on the next
  compile. In reloadable mode the JIT is long lived, so only the storage
  modules are missing."
  []
  (locking compile-lock
    (if (= @build-mode :monolithic)
      (do (compile-module!)
          (when-let [jit @current-jit]
            (install-globals! jit)))
      (install-globals! (ensure-jit!)))
    nil))

(reset! globals/ensure-storage! ensure-global-storage!)

(defn- downcall-handle
  "Builds a panama downcall handle for a JIT symbol address."
  [address return-type param-types]
  (when-not address
    (throw (ex-info "JIT symbol has no address" {:address address})))
  (.downcallHandle (Linker/nativeLinker)
                   (MemorySegment/ofAddress (long address))
                   (function-descriptor return-type param-types)
                   (make-array Linker$Option 0)))

(defn- install-stub!
  "Builds and adds the stub module for stub-symbol: an atomic pointer cell,
  the stable thunk that loads it, and the setter that stores into it. A stub
  is installed once and never recreated. Returns its addresses and setter
  handle."
  [stub-symbol param-types return-type]
  (let [jit (ensure-jit!)
        arena (Arena/ofConfined)]
    (try
      (let [context (llvm/new-context)
            module (llvm/new-module context stub-symbol)
            pointer (llvm/pointer-type context)
            cell (llvm/add-global module pointer
                                 (llvm/c-string arena (str stub-symbol "$cell")))
            thunk-type (llvm/function-type context arena return-type param-types)
            thunk (llvm/add-function module (llvm/c-string arena stub-symbol) thunk-type)
            builder (llvm/new-builder context)
            _ (llvm/append-entry-block thunk builder context arena "entry")
            code (llvm/build-atomic-load builder pointer cell arena "code" 8)
            arguments (mapv #(llvm/get-param thunk %) (range (count param-types)))
            result (llvm/build-call builder thunk-type code arguments arena)
            _ (if return-type
                (llvm/build-ret builder result)
                (llvm/build-ret-void builder))
            _ (llvm/dispose-builder builder)
            ;; The cell starts at an unbound body rather than null, so a call
            ;; that races an install traps in abort instead of jumping to
            ;; address zero. The unbound body has the stub's signature.
            abort-type (llvm/function-type context arena nil [])
            abort (llvm/add-function module (llvm/c-string arena "abort") abort-type)
            unbound (llvm/add-function module
                                         (llvm/c-string arena (str stub-symbol "$unbound"))
                                         thunk-type)
            unbound-builder (llvm/new-builder context)
            _ (llvm/append-entry-block unbound unbound-builder context arena "entry")
            _ (llvm/build-call unbound-builder abort-type abort [] arena)
            _ (llvm/build-unreachable unbound-builder)
            _ (llvm/dispose-builder unbound-builder)
            _ (llvm/set-initializer cell unbound)
            thunk-type (llvm/function-type context arena return-type param-types)
            thunk (llvm/add-function module (llvm/c-string arena stub-symbol) thunk-type)
            builder (llvm/new-builder context)
            _ (llvm/append-entry-block thunk builder context arena "entry")
            code (llvm/build-atomic-load builder pointer cell arena "code" 8)
            arguments (mapv #(llvm/get-param thunk %) (range (count param-types)))
            result (llvm/build-call builder thunk-type code arguments arena)
            _ (if return-type
                (llvm/build-ret builder result)
                (llvm/build-ret-void builder))
            _ (llvm/dispose-builder builder)
            setter-type (llvm/function-type context arena nil [:ptr])
            setter (llvm/add-function module (llvm/c-string arena (str stub-symbol "$set"))
                                      setter-type)
            setter-builder (llvm/new-builder context)
            _ (llvm/append-entry-block setter setter-builder context arena "entry")
            _ (llvm/build-atomic-store setter-builder (llvm/get-param setter 0) cell arena 8)
            _ (llvm/build-ret-void setter-builder)
            _ (llvm/dispose-builder setter-builder)
            _ (llvm/verify module arena)
            added? (volatile! false)]
        (try
          (vreset! added? true)
          (llvm/add-module-to-jit! jit module context)
          (let [thunk-address (llvm/lookup-address jit (llvm/c-string arena stub-symbol) arena)
                setter-address (llvm/lookup-address
                                jit (llvm/c-string arena (str stub-symbol "$set")) arena)]
            {:thunk-address thunk-address
             :setter-address setter-address
             :setter-handle (downcall-handle setter-address nil [:ptr])})
          (finally
            (when-not @added?
              (llvm/dispose-module module)
              (llvm/dispose-context context)))))
      (finally
        (.close arena)))))

(defn- install-body!
  "Adds body bitcode as a new module and points the stub's cell at it. The
  old body stays mapped, so a native frame executing it is not disturbed."
  [stub-symbol body-symbol bitcode]
  (let [stub (get @installed-stubs stub-symbol)
        _ (when-not stub
            (throw (ex-info (str "No installed stub " stub-symbol)
                            {:stub stub-symbol})))
        jit (ensure-jit!)
        arena (Arena/ofConfined)]
    (try
      (let [context (llvm/new-context)
            module (llvm/parse-bitcode context bitcode arena)
            added? (volatile! false)]
        (try
          (when-not (= :none @optimization-level)
            (llvm/optimize-module! module arena @optimization-level))
          (vreset! added? true)
          (llvm/add-module-to-jit! jit module context)
          (let [body-address (llvm/lookup-address jit (llvm/c-string arena body-symbol) arena)]
            (when-not body-address
              (throw (ex-info (str "JIT has no body symbol " body-symbol)
                              {:body body-symbol})))
            (.invokeWithArguments (:setter-handle stub)
                                  (object-array [(MemorySegment/ofAddress (long body-address))]))
            body-address)
          (finally
            (when-not @added?
              (llvm/dispose-module module)
              (llvm/dispose-context context)))))
      (finally
        (.close arena)))))

(defn- ensure-stub-for-entry!
  "Gives entry a stub symbol for its signature, minting and installing one
  if needed, and keeps the JVM thunk handle in step with the current stub.
  name is the entry's qualified name; the stub symbol is minted from the
  native symbol, so the namespace is part of it."
  [name entry]
  (let [signature (procedure-signature entry)
        stubs (:stubs entry {})
        revision (inc (:sig-counter entry 0))
        base-symbol (procedure-symbol entry)
        stub-symbol (or (get stubs signature)
                        (if (= revision 1)
                          base-symbol
                          (str base-symbol "$sig" revision)))
        entry (assoc entry
                     :stubs (assoc stubs signature stub-symbol)
                     :sig-counter revision
                     :stub-symbol stub-symbol
                     :stub-signature signature)]
    (when-not (contains? @installed-stubs stub-symbol)
      (let [stub (install-stub! stub-symbol (:param-types entry) (:return-type entry))]
        (swap! installed-stubs assoc stub-symbol
               (merge stub {:procedure name :signature signature}))))
    (when (not= stub-symbol (:stub-symbol (get @procedure-handles name)))
      (let [stub (get @installed-stubs stub-symbol)]
        (swap! procedure-handles assoc name
               {:stub-symbol stub-symbol
                :handle (downcall-handle (:thunk-address stub)
                                         (:return-type entry)
                                         (:param-types entry))})))
    entry))

(defn- inference-environment
  "The environment expression-type resolves names in: the parameters plus the
  namespace the body is written in, which unqualified calls and global
  references resolve against."
  [referring-namespace param-names param-types]
  (into {::namespace referring-namespace}
        (map (fn [param-name param-type] [param-name {:type param-type}])
             param-names param-types)))

(defn- infer-return-type
  "The return type inferred from an entry's AST, or nil for a void body."
  [entry]
  (expression-type (inference-environment (:namespace entry)
                                          (:param-names entry)
                                          (:param-types entry))
                   (last (:ast entry))))

(defn- settle-signatures!
  "Recomputes every inferred return type until the registry is consistent.
  A return-type change can change the inferred return type of a caller, so
  a single pass is not enough. Explicit return types are never changed."
  []
  (let [limit (inc (count @procedure-registry))]
    (loop [iteration 0]
      (let [changed? (volatile! false)]
        (doseq [[name entry] @procedure-registry]
          (when-not (:explicit-return-type entry)
            (let [inferred (infer-return-type entry)]
              (when (not= inferred (:return-type entry))
                (swap! procedure-registry assoc-in [name :return-type] inferred)
                (vreset! changed? true)))))
        (when @changed?
          (when (>= iteration limit)
            (throw (ex-info "Inferred procedure return types did not settle" {})))
          (recur (inc iteration))))))
  nil)

(defn- reemit-all-bodies!
  "Rebuilds every registered procedure from its AST in the current mode.
  Monolithic mode re-emits public names and marks the module dirty for the
  next whole-module build. Reloadable mode assigns a stub to every entry
  first, then builds every body, then installs them, so a codegen failure
  leaves the old bodies playing. Signature changes are handled by a full
  re-emission: return types are inferred, and the fixpoint above needs all
  entries to agree before any body is emitted."
  []
  (settle-signatures!)
  (if (= @build-mode :monolithic)
    (do
      (doseq [[name entry] @procedure-registry]
        (let [{:keys [bitcode callees globals]}
              (binding [*call-target* :public]
                (build-procedure! (:namespace entry) (:name entry)
                                  (procedure-symbol entry)
                                  (:param-names entry) (:param-types entry)
                                  (:return-type entry) (:ast entry)
                                  (void-declared? entry)))]
          (swap! procedure-registry assoc name
                 (assoc entry
                        :bitcode bitcode
                        :callees callees
                        :globals globals
                        :body-symbol (procedure-symbol entry)
                        :stub-symbol nil
                        :stub-signature nil
                        :stubs {}
                        :sig-counter 0))))
      (reset! module-dirty true))
    (let [_ (install-globals! (ensure-jit!))
          entries (into {} (map (fn [[name entry]]
                                  [name (ensure-stub-for-entry! name entry)])
                                @procedure-registry))
          _ (swap! procedure-registry merge entries)
          pending (into {}
                        (map (fn [[name entry]]
                               (let [body-symbol (next-body-symbol name)
                                     {:keys [bitcode callees globals]}
                                     (binding [*call-target* :stub]
                                       (build-procedure! (:namespace entry)
                                                         (:name entry)
                                                         body-symbol
                                                         (:param-names entry)
                                                         (:param-types entry)
                                                         (:return-type entry)
                                                         (:ast entry)
                                                         (void-declared? entry)))]
                                 [name {:body-symbol body-symbol
                                        :bitcode bitcode
                                        :callees callees
                                        :globals globals}]))
                             entries))]
      (doseq [[name {:keys [body-symbol bitcode callees globals]}] pending]
        (let [entry (get @procedure-registry name)]
          (install-body! (:stub-symbol entry) body-symbol bitcode)
          (swap! procedure-registry assoc name
                 (assoc entry
                        :body-symbol body-symbol
                        :bitcode bitcode
                        :callees callees
                        :globals globals
                        :generation (inc (:generation entry 0))))))))
  nil)

(defn define-var!
  "Defines or redefines a global from already parsed parts. The defvar macro
  calls this at macro expansion time; tests call it directly.

  defvar always initialises: a first definition stores initial-value in fresh
  storage. A redefinition with the same type keeps the live value, like
  defonce, and only records the new source initial value. A redefinition with
  a different type mints a new native symbol and reinstalls storage, then
  re-emits every procedure so already-compiled code reads the new layout.

  Returns the new global entry."
  [namespace name type initial-value]
  (locking compile-lock
    (let [[entry type-changed?] (globals/register! namespace name type initial-value)]
      (cond
        type-changed?
        (do
          (when (= @build-mode :reloadable)
            (let [jit (ensure-jit!)]
              (install-globals! jit)
              (reemit-all-bodies!)))
          (when (= @build-mode :monolithic)
            (reemit-all-bodies!))
          entry)

        (= @build-mode :monolithic)
        (do
          (reset! module-dirty true)
          entry)

        :else
        (do
          (when @current-jit
            (install-globals! @current-jit))
          entry)))))

(defn- warn!
  "Writes a definition-time warning to *err*. A definition that shadows an
  existing name is allowed, so a warning is the only signal that the compiler
  will still prefer the extern or the built in form."
  [message]
  (binding [*out* *err*]
    (println (str "WARNING: " message)))
  nil)

(defn define-proc!
  "Defines or redefines a procedure from already parsed parts. The defproc
  macro calls this at macro expansion time; tests call it directly.
  In monolithic mode the body is emitted under the public name and the
  whole module is rebuilt lazily on the next call. In reloadable mode the
  stub is installed on first definition and a redefinition installs one new
  body. A signature change re-emits every body because inferred return
  types must settle across the whole call graph."
  [namespace name param-names param-types body-forms explicit-return-type static?]
  (names/valid-short-name! "Procedure" name)
  ;; clodin.core holds the procedures visible unqualified everywhere, such as
  ;; not, so it has to be loaded before a body that may call one is compiled.
  (when-not (= namespace names/core-namespace)
    (require-core!))
  ;; print and println lower to the runtime's procedures, so they have to be
  ;; registered before any body that prints is compiled.
  (when-not (= namespace runtime-namespace)
    (require-runtime!))
  (when (contains? @external-registry name)
    (warn! (str "procedure " (names/qualified-name namespace name)
                " has the same name as the external function " name
                "; an unqualified call from another namespace reaches the"
                " extern")))
  (when (and static? (= @build-mode :reloadable))
    (throw (ex-info "Static procedures are not implemented yet"
                    {:procedure name})))
  (when (some #{:void} param-types)
    (throw (ex-info (str "Procedure " name " cannot take a :void parameter")
                    {:procedure name :param-types (vec param-types)})))
  (when (and explicit-return-type
             (not= :void explicit-return-type)
             (not (contains? scalar-types explicit-return-type)))
    (throw (ex-info (str "Procedure " name " declares an unsupported return type "
                         (pr-str explicit-return-type))
                    {:procedure name :return-type explicit-return-type})))
  (let [environment (inference-environment namespace param-names param-types)
        ;; A declared return type is never inferred, and :void means the body
        ;; is a statement, so there is no value to infer from.
        inferred (when-not explicit-return-type
                   (expression-type environment (last body-forms)))
        return-type (if (= :void explicit-return-type)
                      nil
                      (or explicit-return-type inferred))
        definition {:namespace namespace
                    :name name
                    :param-names (vec param-names)
                    :param-types (vec param-types)
                    :return-type return-type
                    :explicit-return-type explicit-return-type
                    :ast body-forms
                    :kind (if static? :static :reloadable)}]
    (locking compile-lock
      (let [qualified (names/qualified-name namespace name)
            existing (get @procedure-registry qualified)
            signature (procedure-signature definition)
            signature-changed? (and existing
                                    (not= signature (procedure-signature existing)))]
        (cond
          (= @build-mode :monolithic)
          (if signature-changed?
            (let [snapshot @procedure-registry]
              (swap! procedure-registry assoc qualified (merge existing definition))
              (try
                (reemit-all-bodies!)
                (catch Throwable error
                  (reset! procedure-registry snapshot)
                  (throw error))))
            (let [emitted-name (procedure-symbol definition)
                  {:keys [bitcode callees globals]}
                  (binding [*call-target* :public]
                    (build-procedure! namespace name emitted-name
                                      (:param-names definition)
                                      (:param-types definition) return-type body-forms
                                      (void-declared? definition)))]
              (swap! procedure-registry assoc qualified
                     (assoc (merge existing definition)
                            :body-symbol emitted-name
                            :bitcode bitcode
                            :callees callees
                            :globals globals
                            :stub-symbol nil
                            :stub-signature nil
                            :stubs {}
                            :sig-counter 0
                            :generation (inc (:generation existing 0))))
              (reset! module-dirty true)))

          (nil? existing)
          (let [provisional (assoc definition
                                 :body-symbol nil
                                 :bitcode nil
                                 :callees {}
                                 :stub-symbol nil
                                 :stub-signature nil
                                 :stubs {}
                                 :sig-counter 0
                                 :generation 1)]
            (swap! procedure-registry assoc qualified provisional)
            (try
              (let [entry (ensure-stub-for-entry! qualified provisional)]
                (swap! procedure-registry assoc qualified entry)
                (let [body-symbol (next-body-symbol (procedure-symbol entry))
                      {:keys [bitcode callees globals]}
                      (binding [*call-target* :stub]
                        (build-procedure! namespace name body-symbol
                                          (:param-names entry) (:param-types entry)
                                          (:return-type entry) body-forms
                                          (void-declared? entry)))]
                  (install-body! (:stub-symbol entry) body-symbol bitcode)
                  (swap! procedure-registry assoc qualified
                         (assoc entry
                                :body-symbol body-symbol
                                :bitcode bitcode
                                :callees callees
                                :globals globals
                                :generation 1))))
              (catch Throwable error
                (swap! procedure-registry dissoc qualified)
                (swap! procedure-handles dissoc qualified)
                (throw error))))

          signature-changed?
          (let [snapshot @procedure-registry
                handles-snapshot @procedure-handles]
            (swap! procedure-registry assoc qualified
                   (assoc (merge existing definition)
                          :generation (inc (:generation existing 0))))
            (try
              (reemit-all-bodies!)
              (catch Throwable error
                (reset! procedure-registry snapshot)
                (reset! procedure-handles handles-snapshot)
                (throw error))))

          :else
          (let [stub-symbol (:stub-symbol existing)
                _ (when-not stub-symbol
                    (throw (ex-info (str "Procedure " qualified
                                         " has no stub in reloadable mode")
                                    {:procedure qualified})))
                body-symbol (next-body-symbol (procedure-symbol definition))
                {:keys [bitcode callees globals]}
                (binding [*call-target* :stub]
                  (build-procedure! namespace name body-symbol
                                    (:param-names definition)
                                    (:param-types definition) return-type body-forms
                                    (void-declared? definition)))]
            (install-body! stub-symbol body-symbol bitcode)
            (swap! procedure-registry assoc qualified
                   (assoc (merge existing definition)
                          :body-symbol body-symbol
                          :bitcode bitcode
                          :globals globals
                          :callees callees
                          :generation (inc (:generation existing 0)))))))))
  nil)

(defn reset-module!
  "Disposes the JIT, drops every stub, body, handle and global, and rebuilds
  and loads every procedure from its AST in the current mode. This
  invalidates every native function pointer and every JVM downcall handle,
  so it must not run while native code is executing."
  []
  (locking compile-lock
    (let [jit @current-jit]
      (reset! current-jit nil)
      (reset! procedure-handles {})
      (reset! installed-stubs {})
      (reset! module-dirty true)
      (globals/reset-installations!)
      (when jit
        (llvm/dispose-jit jit))
      (reemit-all-bodies!)
      nil)))

(defn set-build-mode!
  "Switches between :monolithic and :reloadable. Switching resets the JIT and
  re-emits every procedure from its AST, so it must not run while native code
  is executing."
  [mode]
  (when-not (#{:monolithic :reloadable} mode)
    (throw (ex-info (str "Unknown build mode: " mode) {:mode mode})))
  (when-not (= mode @build-mode)
    (reset! build-mode mode)
    (reset-module!))
  mode)

(defn current-build-mode
  "The current build mode, :monolithic or :reloadable."
  []
  @build-mode)

;;; ---------------------------------------------------------------------------
;;; Whole module build : linked bitcode -> in-process JIT
;;; ---------------------------------------------------------------------------

(defn- link-procedures
  "Parses the stored bitcode of the given procedures into a single linked
  module in the given context. A procedure module only declares the globals it
  reads and the procedures it calls, so the storage modules of the globals
  those procedures use are linked in too. The print runtime is ordinary
  procedures now, so it arrives through the call graph like everything else."
  [procedures context arena]
  (when (empty? procedures)
    (throw (ex-info "No clodin procedures to link" {})))
  (let [used-globals (into #{} (mapcat :globals) (vals procedures))
        specs (filter (fn [[symbol _type _value]] (contains? used-globals symbol))
                      (globals/storage-specs))
        base (global-storage-module context specs arena)]
    (reduce (fn [merged {:keys [bitcode]}]
              (llvm/link-into! merged (llvm/parse-bitcode context bitcode arena)))
            base
            (vals procedures))))

(defn- link-module
  "Parses every registered procedure's stored bitcode into a single linked
  module in the given context."
  [context arena]
  (when (empty? @procedure-registry)
    (throw (ex-info "No clodin procedures registered to compile" {})))
  (link-procedures @procedure-registry context arena))

(defn- used-global-symbols
  "The native symbols of the globals that the linked module defines. A
  monolithic build links the storage modules into the procedure module, so
  the addresses are recorded from the module that was just loaded instead of
  installing a second copy."
  [module]
  (let [specs (globals/storage-specs)]
    (into []
          (keep (fn [[symbol _type _value]]
                  (let [arena (Arena/ofConfined)]
                    (try
                      (when-not (llvm/null?
                                 (llvm/named-global module
                                                    (llvm/c-string arena (str symbol))))
                        symbol)
                      (finally
                        (.close arena))))))
          specs)))

(defn- build-handles
  "Builds a panama downcall handle for every registered procedure from its
  machine code address in the given JIT. Monolithic mode installs bodies under
  their public names, so the handle points straight at the body."
  [jit arena]
  (into {}
        (map (fn [[name {:keys [param-types return-type body-symbol]}]]
               (let [address (llvm/lookup-address jit
                                                  (llvm/c-string arena body-symbol)
                                                  arena)]
                 (when-not address
                   (throw (ex-info (str "JIT has no symbol " body-symbol
                                        " for procedure " name)
                                   {:procedure name :symbol body-symbol})))
                 [name {:stub-symbol nil
                        :handle (downcall-handle address return-type param-types)}])))
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
            _ (record-global-addresses! jit (used-global-symbols module))
            _ (install-writer! jit)
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
  marked it dirty. This is the monolithic mode build path; reloadable mode
  installs bodies eagerly and never compiles here."
  []
  (locking compile-lock
    (when (and (= @build-mode :monolithic) @module-dirty)
      (recompile-module!)
      (reset! module-dirty false))
    nil))

(defn procedure-entry
  "The registry entry for a procedure reference, as [qualified-name entry], or
  nil when there is none.

  A qualified name is used as written. A short name is accepted when exactly
  one registered procedure has it, which is what a REPL user types at
  invoke-procedure or and at the inspection helpers; the language itself
  always resolves a name through the defining namespace and clodin.core."
  [procedure-name]
  (let [reference (str procedure-name)]
    (or (when-let [entry (get @procedure-registry reference)]
          [reference entry])
        (let [matches (filter (fn [[_name entry]] (= reference (:name entry)))
                              @procedure-registry)]
          (cond
            (= 1 (count matches)) (first matches)
            (empty? matches) nil
            :else
            (throw (ex-info (str "Ambiguous procedure name " reference
                                 "; it names " (string/join ", " (map first matches)))
                            {:procedure reference
                             :candidates (mapv first matches)})))))))

(defn invoke-procedure
  "Calls a native procedure through its panama downcall handle, coercing
  the clojure arguments to the declared types. If procedures have been
  registered since the last compile, the module is compiled and loaded
  first."
  [procedure-name arguments]
  (let [[name procedure] (or (procedure-entry procedure-name)
                             (throw (ex-info (str "Unknown clodin procedure: "
                                                  procedure-name)
                                             {:procedure procedure-name})))]
    (compile-module!)
    (let [handle-entry (get @procedure-handles name)
          handle (:handle handle-entry)]
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

(defn- main-procedure-name
  "The qualified name of the registered procedure whose short name is main,
  or nil."
  [procedures]
  (some (fn [[name entry]] (when (= "main" (:name entry)) name)) procedures))

(defn- main-namespace
  "The namespace that defines the procedure named main, or nil."
  []
  (some-> (main-procedure-name @procedure-registry)
          (#(:namespace (get @procedure-registry %)))))

(defn- namespace-closure-from
  "The union of the call-graph closures of root-namespace and
  extra-namespaces."
  [root-namespace extra-namespaces]
  (reduce (fn [closure namespace]
            (into closure (namespace-closure (str namespace))))
          (namespace-closure (str root-namespace))
          extra-namespaces))

(defn procedures-for-namespace
  "Returns the procedures that would be linked for root-namespace and the
  namespaces it calls into. Requires root-namespace and any extra namespaces
  first. Unlike build-executable!, this does not require a procedure named
  main, so it also works for library namespaces.

  Options:
    :extra-namespaces  additional namespaces to include"
  ([root-namespace]
   (procedures-for-namespace root-namespace {}))
  ([root-namespace {:keys [extra-namespaces] :or {extra-namespaces []}}]
   (let [root-namespace (symbol (str root-namespace))
         extra-namespaces (mapv #(symbol (str %)) extra-namespaces)]
     (doseq [namespace (cons root-namespace extra-namespaces)]
       (require namespace))
     (procedures-in-namespaces
      (namespace-closure-from root-namespace extra-namespaces)))))

(defn- procedures-for-linking
  "Re-emits the chosen procedures from their AST for an offline link: plain
  symbols, direct calls and no stubs. Returns registry entries with fresh
  bitcode.

  The offline symbols drop the namespace, because the system linker exports
  them and the entry point has to be exactly main. Procedure short names must
  therefore be unique across the linked set."
  [procedures]
  (binding [*call-target* :public
            *symbol-style* :plain]
    (into {}
          (map (fn [[name entry]]
                 (let [{:keys [bitcode callees globals]}
                       (build-procedure! (:namespace entry) (:name entry)
                                         (:name entry)
                                         (:param-names entry) (:param-types entry)
                                         (:return-type entry) (:ast entry)
                                         (void-declared? entry))]
                   [name (assoc entry
                                :bitcode bitcode
                                :callees callees
                                :globals globals
                                :body-symbol (:name entry))])))
          procedures)))

(defn with-linked-module
  "Calls f with a linked module built from procedures and the arena used to
  create it. The module, context and arena are disposed after f returns."
  [procedures f]
  (let [procedures (procedures-for-linking procedures)]
    (llvm/init-targets!)
    (let [arena (Arena/ofConfined)]
      (try
        (let [context (llvm/new-context)
              module (link-procedures procedures context arena)]
          (try
            (f module arena)
            (finally
              (llvm/dispose-module module)
              (llvm/dispose-context context))))
        (finally
          (.close arena))))))

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
         extra-namespaces (mapv #(symbol (str %)) extra-namespaces)
         procedures (procedures-for-namespace root-namespace
                                              {:extra-namespaces extra-namespaces})
         entry-namespace (main-namespace)
         has-main? (some? (main-procedure-name procedures))
         procedures (if (and entry-namespace (not has-main?))
                      (merge procedures (procedures-for-namespace entry-namespace))
                      procedures)]
     (when (empty? procedures)
       (throw (ex-info (str "No clodin procedures found in " root-namespace
                            " or the namespaces it calls")
                       {:namespace root-namespace})))
     (when-not (some? (main-procedure-name procedures))
       (throw (ex-info (str "No procedure named main in " root-namespace
                            " or the namespaces it calls")
                       {:namespace root-namespace
                        :namespaces (set (map :namespace (vals procedures)))})))
     (llvm/init-targets!)
     (let [arena (Arena/ofConfined)]
       (try
         (let [context (llvm/new-context)
               module (link-procedures (procedures-for-linking procedures) context arena)
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
           (.close arena)))))))

(defn- shared-library-extension
  []
  (if (string/includes? (System/getProperty "os.name") "Mac") "dylib" "so"))

(defn- shared-library-linker-flags
  []
  (if (string/includes? (System/getProperty "os.name") "Mac")
    ["-dynamiclib"]
    ["-shared"]))

(defn build-library!
  "Builds a shared library from the procedures defined in root-namespace and
  the namespaces they call into. Unlike build-executable!, no procedure named
  main is required. The procedures keep external linkage, so the linker
  exports them as dynamic symbols.

  Options:
    :output              output path (default libclodin.so or libclodin.dylib)
    :optimization-level  :none, :O0, :O1, :O2 or :O3 (defaults to the
                         compiler's current level)
    :linker              linker driver command (default \"cc\")
    :linker-args         extra arguments passed to the linker driver
    :extra-namespaces    additional namespaces to link

  Returns the output path. The intermediate object file is removed after a
  successful link."
  ([root-namespace]
   (build-library! root-namespace {}))
  ([root-namespace {:keys [output linker linker-args extra-namespaces]
                    :or {output (str "libclodin." (shared-library-extension))
                         linker "cc"
                         linker-args []
                         extra-namespaces []}
                    :as options}]
   (let [root-namespace (symbol (str root-namespace))
         extra-namespaces (mapv #(symbol (str %)) extra-namespaces)
         procedures (procedures-for-namespace root-namespace
                                              {:extra-namespaces extra-namespaces})]
     (when (empty? procedures)
       (throw (ex-info (str "No clodin procedures found in " root-namespace
                            " or the namespaces it calls")
                       {:namespace root-namespace})))
     (llvm/init-targets!)
     (let [arena (Arena/ofConfined)]
       (try
         (let [context (llvm/new-context)
               module (link-procedures (procedures-for-linking procedures) context arena)
               target-machine (llvm/create-target-machine arena {:pic? true})]
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
                            (concat [linker]
                                    (shared-library-linker-flags)
                                    ["-o" output object-path]
                                    linker-args))]
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
           (.close arena)))))))

;;; ---------------------------------------------------------------------------
;;; Compilation entry point (macro)
;;; ---------------------------------------------------------------------------

(def ^:private compiler-forms
  "The names the compiler lowers itself. A definition may shadow one, because
  an unqualified reference always means the form, so a procedure or global
  with one of these names is reachable only by its qualified name."
  '#{+ - * / < <= > >= = if when while let set load store pointer-add
     buffer-get buffer-set to-pointer to-float to-f32 to-i32 to-i64 sqrt abs
     min max print println stack-allocate pointer-call})

(defn- defvar-form
  "Parses a defvar form, registers the global through define-var! and returns
  the clojure def that binds the name to the global's JVM handle. The handle
  implements IDeref and IAtom2, so reset!, swap!, deref and
  compare-and-set! work on it from Clojure.

  The initial value must be a literal, because it is compiled into the
  storage module as an LLVM constant at macroexpansion time."
  [namespace name type initial-value]
  (when-not (symbol? name)
    (throw (ex-info "Global name must be a symbol" {:name name})))
  (when (contains? compiler-forms name)
    (warn! (str "global " (names/qualified-name namespace name)
                " shadows the compiler form " name
                "; an unqualified reference means the form")))
  (when-not (or (number? initial-value) (boolean? initial-value)
                (nil? initial-value))
    (throw (ex-info (str "defvar needs a literal initial value (a number, a"
                         " boolean or nil), got " (pr-str initial-value))
                    {:name name :initial-value initial-value})))
  (define-var! namespace (str name) type initial-value)
  `(def ~name (globals/global-variable ~(names/qualified-name namespace (str name)))))

(defn- defproc-form
  "Parses a defproc form, defines the procedure through define-proc! and
  returns the clojure defn form that calls it. namespace is the namespace
  the procedure is defined in, used by build-executable! to select
  procedures."
  [namespace name params body]
  (when-not (symbol? name)
    (throw (ex-info "Procedure name must be a symbol" {:name name})))
  (when (contains? compiler-forms name)
    (warn! (str "procedure " (names/qualified-name namespace name)
                " shadows the compiler form " name
                "; an unqualified reference means the form, so call it as "
                (names/qualified-name namespace name))))
  (when-not (vector? params)
    (throw (ex-info "Procedure parameters must be a vector" {:params params})))
  (when-not (even? (count params))
    (throw (ex-info "Procedure parameters must come in name, type pairs"
                    {:params params})))
  (let [param-pairs (partition 2 params)
        param-names (mapv first param-pairs)
        param-types (mapv second param-pairs)
        explicit-return-type (when (keyword? (first body)) (first body))
        body-forms (if explicit-return-type (next body) body)]
    (define-proc! namespace (str name) param-names param-types body-forms
                  explicit-return-type
                  (boolean (:static (meta name))))
    `(defn ~name [~@param-names]
       (invoke-procedure ~(names/qualified-name namespace (str name))
                         [~@param-names]))))

(defmacro defvar
  "Defines a top level mutable variable that native code and Clojure share.

  (defvar running? :boolean true)

  In clodin source the name reads as a value and (set running? false) writes
  it. Every read is an atomic acquire load and every write an atomic release
  store, so a native loop that spins on the name sees a write from another
  thread and the optimizer cannot hoist the load out of the loop:

  (defproc spin-until-stopped [] :i64
    (let [turns :i64 0]
      (while running?
        (set turns (+ turns 1)))
      turns))

  In Clojure the name is bound to a handle that implements
  clojure.lang.IDeref and clojure.lang.IAtom2, so @running?, (reset!
  running? false), (swap! counter inc), (compare-and-set! ...),
  (reset-vals! ...) and (swap-vals! ...) all work.

  The global is registered as namespace/name with the native symbol
  namespace.name, so two namespaces can both define counter.

  defvar always initialises: the first definition stores the initial value in
  the storage module. Re-evaluating a defvar with the same type keeps the live
  value, like defonce, and only records the new source initial value for the
  next reset. Changing the type is handled like a procedure signature change:
  a new native symbol is minted, fresh storage is installed and every
  procedure is re-emitted against the new layout, so existing native code
  never reads the new storage through the old type.

  Supported types are :boolean, :i64, :i32, :f64, :f32 and :ptr. A :boolean
  global is stored as a 32 bit 0/1 slot, because LLVM can only perform atomic
  accesses on byte-sized types. A :ptr global starts as nil and is given an
  address later with reset!.

  The value lives in JIT memory: a full reset or a monolithic rebuild
  reinitialises every global from its source initial value, and globals do not
  survive either."
  [name type initial-value]
  (defvar-form (str *ns*) name type initial-value))

(defmacro defproc
  "Defines a native clodin procedure.

  (defproc twice [number :i64] (* number 2))

  The procedure is registered as namespace/name, where the namespace is the
  namespace the form is evaluated in, and its native symbol is
  namespace.name. Calls from the same namespace and from clodin.core may use
  the short name; a call from anywhere else must be written qualified.

  At macroexpansion time the body is type checked (untyped constant
  integers are 64 bit integers) and rendered as llvm ir through the LLVM C
  API. In the default :monolithic mode the bitcode is stored in the
  registry and the whole module is compiled lazily on the first call. In
  :reloadable mode the stub is installed and the body is loaded eagerly,
  so a redefinition takes effect in native callers on their next call.

  The generated Clojure wrapper calls the procedure through
  invoke-procedure. Use (println ...) and (print ...) inside the body to write
  to *out*: the print runtime calls back into the JVM on the calling thread,
  so with-out-str and CIDER work. extern printf stays raw libc output and
  goes to the process stdout.

  An explicit return type can be given by putting a type keyword before
  the body forms; without it the return type is inferred from the last
  body expression. Declaring :void makes the body a statement, so whatever the
  last form produces is discarded:

  (defproc write-bytes [data :ptr length :i64] :void
    (if (is-null clodin-write)
      (write (to-i32 1) data length)
      (pointer-call clodin-write [:ptr :i64] :void data length)))

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
           (:namespace (get @procedure-registry "clodin.compiler/add-both"))))))

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

;; if branches for real, so an arm may have a side effect.
(defproc if-store [pointer :ptr flag :boolean]
  (if flag
    (store :i64 pointer 1)
    (store :i64 pointer 2)))

;; Only the taken arm runs, so the untaken arm cannot touch the local.
(defproc if-arms-are-exclusive [flag :boolean]
  (let [turns :i64 0]
    (if flag
      (set turns (+ turns 1))
      (set turns (+ turns 10)))
    turns))

(defproc if-nested [a :i64 b :i64] :i64
  (if (< a b)
    (if (= a 0) 100 200)
    300))

(defproc if-inside-loop [limit :i64] :i64
  (let [sum :i64 0
        index :i64 0]
    (while (< index limit)
      (set sum (+ sum (if (= index 0) 10 1)))
      (set index (+ index 1)))
    sum))

(defproc when-store [pointer :ptr flag :boolean]
  (store :i64 pointer 0)
  (when flag
    (store :i64 pointer 7))
  (load :i64 pointer))

(defproc when-composed-not-store [pointer :ptr flag :boolean]
  (store :i64 pointer 0)
  (when (not flag)
    (store :i64 pointer 7))
  (load :i64 pointer))

(defproc when-runs-one-side-effect [flag :boolean]
  (let [turns :i64 0]
    (when flag
      (set turns (+ turns 5)))
    turns))

;; A literal false condition must compile, not be mistaken for a missing one.
(defproc when-literal-false []
  (let [turns :i64 0]
    (when false
      (set turns (+ turns 5)))
    turns))

(defproc while-literal-false []
  (let [turns :i64 0]
    (while false
      (set turns (+ turns 5)))
    turns))

(defproc choice-abs [x :f64]
  (if (< x 0.0) (abs x) x))

(defproc halve-f32 [x :f32]
  (/ x (to-f32 2)))

(defproc f32-from-f64 [x :f64]
  (to-f32 x))

(defproc f32-from-i64 [x :i64]
  (to-f32 x))

(deftest test-if
  (testing "a value if produces the taken arm's value"
    (is (= 2 (if-choose 2 5)))
    (is (= 2 (if-choose 5 2)))
    (is (= 3.0 (choice-abs -3.0)))
    (is (= 3.0 (choice-abs 3.0))))

  (testing "a statement if runs exactly one arm"
    (let [arena (Arena/ofShared)
          buffer (.allocate arena 8)]
      (try
        (if-store buffer true)
        (is (= 1 (.get buffer ValueLayout/JAVA_LONG 0)))
        (if-store buffer false)
        (is (= 2 (.get buffer ValueLayout/JAVA_LONG 0)))
        (finally
          (.close arena)))))

  (testing "the untaken arm does not run"
    (is (= 1 (if-arms-are-exclusive true)))
    (is (= 10 (if-arms-are-exclusive false))))

  (testing "nested and loop-hosted ifs"
    (is (= 100 (if-nested 0 5)))
    (is (= 200 (if-nested 1 5)))
    (is (= 300 (if-nested 9 5)))
    (is (= 12 (if-inside-loop 3)))))

(deftest test-branch-bookkeeping
  (testing "a callee referenced only inside a branch is still recorded"
    (define-proc! "clodin.compiler" "branch-test-leaf" [] [] [5] nil false)
    (define-proc! "clodin.compiler" "branch-test-caller" ['flag] [:boolean]
                  '[(if flag (branch-test-leaf) 1)] :i64 false)
    (is (contains? (:callees (get @procedure-registry
                                  "clodin.compiler/branch-test-caller"))
                   "clodin.compiler/branch-test-leaf")))

  (testing "a global referenced only inside a branch is still recorded"
    (define-proc! "clodin.compiler" "branch-test-global-reader" ['flag] [:boolean]
                  '[(if flag globals-test-counter 0)] :i64 false)
    (is (contains? (:globals (get @procedure-registry
                                  "clodin.compiler/branch-test-global-reader"))
                   "clodin.compiler.globals-test-counter"))))

(deftest test-if-value-uses-a-phi
  (testing "the join block merges both arms with a phi node"
    (let [ir (requiring-resolve 'clodin.inspect/llvm-ir)]
      (is (string/includes? (ir "if-choose") "phi i64")))))

(deftest test-if-arm-mismatch
  (testing "mixing a value arm with a statement arm is a clear error"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"arms must both produce a value of the same type"
         (define-proc! "clodin.compiler" "if-arm-mismatch" ['pointer] [:ptr]
                       '[(if true 1 (store :i64 pointer 0))] :i64 false)))))

(deftest test-when
  (testing "when runs its body only when the condition is true"
    (let [arena (Arena/ofShared)
          buffer (.allocate arena 8)]
      (try
        (is (= 7 (when-store buffer true)))
        (is (= 0 (when-store buffer false)))
        (finally
          (.close arena)))))

  (testing "a literal false condition is a condition, not a missing one"
    (is (= 0 (when-literal-false)))
    (is (= 0 (while-literal-false))))

  (testing "when has no value, so it cannot be used where one is needed"
    (is (= 5 (when-runs-one-side-effect true)))
    (is (= 0 (when-runs-one-side-effect false)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (define-proc! "clodin.compiler" "when-as-value" [] []
                               '[(let [value :i64 (when true 1)] value)] :i64 false)))))

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
  (println "value = " value))

(defproc print-without-newline [value :i64]
  (print "value = " value))

(defproc print-mixed [flag :boolean ratio :f64 label :ptr]
  (println label " " flag " " ratio))

(defproc truncate-to-i32 [value :i64]
  (to-i32 value))

(defproc widen-to-i64 [value :i32]
  (to-i64 value))

(defproc is-greater [a :i64 b :i64]
  (> a b))

;; not= is not a form; composing not with = gives the same result.
(defproc is-different [a :i64 b :i64]
  (not (= a b)))

(defproc choose-by-flag [flag :boolean a :i64 b :i64]
  (if flag a b))

(defproc count-if-greater [a :i64 b :i64]
  (let [flag :boolean (> a b)]
    (if flag 1 0)))

(defproc flip-flag [flag :boolean]
  (let [current :boolean flag]
    (set current (not current))
    (if current 1 0)))

(defproc negate-flag [flag :boolean]
  (not flag))

(defproc always-true []
  true)

(defproc flag-to-i32 [a :i64 b :i64]
  (to-i32 (> a b)))

(defproc flag-to-i64 [a :i64 b :i64]
  (to-i64 (> a b)))

(defproc set-flag [pointer :ptr index :i64 value :boolean]
  (buffer-set pointer :boolean index value))

(defproc get-flag [pointer :ptr index :i64]
  (buffer-get pointer :boolean index))

(defproc seed-random [seed :i32]
  (srand seed))

(deftest test-extern-printf
  (testing "printf is callable and returns the number of bytes written"
    (is (= 0 (print-nothing)))))

(deftest test-println-writes-to-out
  (testing "println writes to the calling thread's *out*"
    (is (= "value = 42\n" (with-out-str (print-value 42)))))

  (testing "print does not append a newline"
    (is (= "value = 42" (with-out-str (print-without-newline 42)))))

  (testing "println formats booleans, floats and C strings"
    (let [arena (Arena/ofShared)
          label (.allocateFrom arena "label")]
      (try
        (is (= "label true 1.5\n" (with-out-str (print-mixed true 1.5 label))))
        (finally
          (.close arena)))))

  (testing "a procedure called from a future prints to the conveyed *out*"
    (let [output (deref (future (with-out-str (print-value 7))) 5000 ::timeout)]
      (is (= "value = 7\n" output)))))

(deftest test-build-library
  (let [output (str (System/getProperty "java.io.tmpdir")
                    "/clodin-test-lib-" (System/nanoTime)
                    (if (string/includes? (System/getProperty "os.name") "Mac")
                      ".dylib" ".so"))]
    (try
      (build-library! 'examples.standalone-lib {:output output})
      (let [arena (Arena/ofConfined)]
        (try
          (let [lookup (SymbolLookup/libraryLookup output arena)
                greet-length (.orElseThrow (.find lookup "greet-length"))
                descriptor (FunctionDescriptor/of
                            ValueLayout/JAVA_LONG
                            (into-array java.lang.foreign.MemoryLayout
                                        [ValueLayout/ADDRESS]))
                handle (.downcallHandle (Linker/nativeLinker) greet-length descriptor
                                        (make-array Linker$Option 0))
                text (.allocateFrom arena "library")]
            ;; greet-length calls extern strlen, so the test exercises the
            ;; linked library without writing to the process stdout.
            (is (= 7 (long (.invokeWithArguments handle (object-array [text]))))))
          (finally
            (.close arena))))
      (finally
        (io/delete-file output true)))))

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

(deftest test-to-i64
  (testing "to-i64 sign extends a 32 bit integer"
    (is (= 42 (widen-to-i64 42)))
    (is (= -1 (widen-to-i64 -1)))))

(deftest test-boolean
  (testing "comparisons produce :boolean values that cross the jvm boundary"
    (is (true? (is-greater 5 3)))
    (is (false? (is-greater 3 5))))

  (testing ":boolean parameters and let bindings"
    (is (= 7 (choose-by-flag true 7 9)))
    (is (= 9 (choose-by-flag false 7 9)))
    (is (= 1 (count-if-greater 5 3)))
    (is (= 0 (count-if-greater 3 5))))
    (is (= 1 (flip-flag false)))
    (is (= 0 (flip-flag true)))

  (testing "boolean literals are :boolean"
    (is (true? (always-true))))

  (testing ":boolean widens to i32 and i64"
    (is (= 1 (flag-to-i32 5 3)))
    (is (= 0 (flag-to-i32 3 5)))
    (is (= 1 (flag-to-i64 5 3)))
    (is (= 0 (flag-to-i64 3 5)))))

(deftest test-not
  (testing "not is a procedure in clodin.core, not a compiler form"
    (is (contains? @procedure-registry "clodin.core/not")))

  (testing "not negates a :boolean"
    (is (false? (negate-flag true)))
    (is (true? (negate-flag false))))

  (testing "when composes with not to run the body only when false"
    (let [arena (Arena/ofShared)
          buffer (.allocate arena 8)]
      (try
        (is (= 0 (when-composed-not-store buffer true)))
        (is (= 7 (when-composed-not-store buffer false)))
        (finally
          (.close arena)))))

  (testing "not checks its argument type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"do not match parameters"
                          (define-proc! "clodin.compiler" "not-on-integer" [] []
                                        '[(not 1)] :boolean false)))))

(deftest test-not-equal
  (testing "not= is composed from not and ="
    (is (true? (is-different 1 2)))
    (is (false? (is-different 2 2))))

  (testing "not= is no longer a compiler form"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown clodin procedure: not="
                          (define-proc! "clodin.compiler" "not-equal-removed" [] []
                                        '[(if (not= 1 2) 1 0)] :i64 false)))))

(deftest test-boolean-buffer
  (let [arena (Arena/ofShared)
        buffer (.allocate arena 4)]
    (try
      (set-flag buffer 0 true)
      (set-flag buffer 1 false)
      (set-flag buffer 2 true)
      (is (true? (get-flag buffer 0)))
      (is (false? (get-flag buffer 1)))
      (is (true? (get-flag buffer 2)))
      (is (true? (.get buffer ValueLayout/JAVA_BOOLEAN 0)))
      (is (false? (.get buffer ValueLayout/JAVA_BOOLEAN 1)))
      (finally
        (.close arena)))))

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

;;; ---------------------------------------------------------------------------
;;; Reloadable mode tests
;;;
;;; These use define-proc! at run time instead of defproc forms, because a
;;; defproc form inside deftest would be expanded (and registered) when the
;;; namespace loads, not when the test runs.
;;; ---------------------------------------------------------------------------

(deftest test-reloadable-redefinition-propagates
  (try
    (set-build-mode! :reloadable)
    (define-proc! "clodin.compiler" "reload-test-value" [] [] [1] nil false)
    (define-proc! "clodin.compiler" "reload-test-caller" [] []
                  '[(reload-test-value)] nil false)
    (is (= 1 (invoke-procedure "reload-test-caller" [])))
    (define-proc! "clodin.compiler" "reload-test-value" [] [] [2] nil false)
    (testing "an already compiled native caller sees the new body"
      (is (= 2 (invoke-procedure "reload-test-caller" []))))
    (testing "the body symbol is versioned but the stub keeps its name"
      (is (string/includes? (:body-symbol (get @procedure-registry
                                            "clodin.compiler/reload-test-value"))
                            "$"))
      (is (= "clodin.compiler.reload-test-value"
             (:stub-symbol (get @procedure-registry
                                "clodin.compiler/reload-test-value")))))
    (finally
      (set-build-mode! :monolithic))))

(deftest test-reloadable-preserves-buffer-state
  (let [arena (Arena/ofShared)
        buffer (.allocate arena 8)]
    (try
      (set-build-mode! :reloadable)
      (define-proc! "clodin.compiler" "reload-test-bump" ['pointer] [:ptr]
                    '[(let [value :i64 (load :i64 pointer)]
                        (store :i64 pointer (+ value 1))
                        value)]
                    :i64 false)
      (is (= 0 (invoke-procedure "reload-test-bump" [buffer])))
      (is (= 1 (invoke-procedure "reload-test-bump" [buffer])))
      (define-proc! "clodin.compiler" "reload-test-bump" ['pointer] [:ptr]
                    '[(let [value :i64 (load :i64 pointer)]
                        (store :i64 pointer (+ value 10))
                        value)]
                    :i64 false)
      (testing "the new body reads the memory the old body wrote"
        (is (= 2 (invoke-procedure "reload-test-bump" [buffer])))
        (is (= 12 (invoke-procedure "reload-test-bump" [buffer]))))
      (finally
        (set-build-mode! :monolithic)
        (.close arena)))))

(deftest test-reloadable-signature-change-cascades
  (try
    (set-build-mode! :reloadable)
    (define-proc! "clodin.compiler" "reload-test-producer" [] [] [1] nil false)
    (define-proc! "clodin.compiler" "reload-test-consumer" [] []
                  '[(reload-test-producer)] nil false)
    (is (= 1 (invoke-procedure "reload-test-consumer" [])))
    (define-proc! "clodin.compiler" "reload-test-producer" [] [] [1.5] nil false)
    (testing "the callers are re-emitted and their inferred types follow"
      (is (= 1.5 (invoke-procedure "reload-test-producer" [])))
      (is (= 1.5 (invoke-procedure "reload-test-consumer" [])))
      (is (= :f64 (:return-type (get @procedure-registry
                                  "clodin.compiler/reload-test-consumer")))))
    (testing "the old stub is replaced by a signature stub"
      (is (not= "clodin.compiler.reload-test-producer"
                (:stub-symbol (get @procedure-registry
                                   "clodin.compiler/reload-test-producer")))))
    (finally
      (set-build-mode! :monolithic))))

(deftest test-build-mode-switch
  (try
    (set-build-mode! :reloadable)
    (define-proc! "clodin.compiler" "reload-test-mode" [] [] [7] nil false)
    (is (= 7 (invoke-procedure "reload-test-mode" [])))
    (set-build-mode! :monolithic)
    (is (= :monolithic (current-build-mode)))
    (is (= 7 (invoke-procedure "reload-test-mode" [])))
    (set-build-mode! :reloadable)
    (is (= :reloadable (current-build-mode)))
    (is (= 7 (invoke-procedure "reload-test-mode" [])))
    (finally
      (set-build-mode! :monolithic))))

(deftest test-reloadable-caller-calls-the-stub
  (try
    (set-build-mode! :reloadable)
    (define-proc! "clodin.compiler" "reload-test-ir-callee" [] [] [1] nil false)
    (define-proc! "clodin.compiler" "reload-test-ir-caller" [] []
                  '[(reload-test-ir-callee)] nil false)
    (let [llvm-ir (requiring-resolve 'clodin.inspect/llvm-ir)
          ir (llvm-ir "reload-test-ir-caller")]
      (is (string/includes? ir "@clodin.compiler.reload-test-ir-callee"))
      (is (not (string/includes? ir "@reload-test-ir-callee$"))))
    (finally
      (set-build-mode! :monolithic))))

(deftest test-reloadable-native-loop-sees-new-body
  (let [arena (Arena/ofShared)
        flag (.allocate arena 8)
        out (.allocate arena 8)
        previous-level (current-optimization-level)]
    (try
      (set-optimization-level! :none)
      (set-build-mode! :reloadable)
      (.set flag ValueLayout/JAVA_LONG 0 0)
      (define-proc! "clodin.compiler" "reload-test-loop-synth" [] [] [1] nil false)
      (define-proc! "clodin.compiler" "reload-test-loop-driver" ['flag 'out] [:ptr :ptr]
                    '[(let [index :i64 0]
                        (while (= (load :i64 flag) 0)
                          (buffer-set out :i64 0 (reload-test-loop-synth))
                          (set index (+ index 1)))
                        index)]
                    :i64 false)
      (let [worker (future (invoke-procedure "reload-test-loop-driver" [flag out]))]
        (try
          (Thread/sleep 200)
          (is (= 1 (.get out ValueLayout/JAVA_LONG 0)))
          (define-proc! "clodin.compiler" "reload-test-loop-synth" [] [] [2] nil false)
          (Thread/sleep 200)
          (testing "the running native loop picks up the new body"
            (is (= 2 (.get out ValueLayout/JAVA_LONG 0))))
          (finally
            (.set flag ValueLayout/JAVA_LONG 0 1)))
        (is (integer? (deref worker 10000 ::timeout))))
    (finally
      (.set flag ValueLayout/JAVA_LONG 0 1)
      (set-build-mode! :monolithic)
      (set-optimization-level! previous-level)
      (.close arena)))))

;;; ---------------------------------------------------------------------------
;;; Globals
;;;
;;; The defvar macro expands to a Clojure def plus a registration at load
;;; time, so a defvar inside deftest would register when the namespace loads.
;;; These tests define the globals at load time and drive them from the test
;;; bodies. Procedure definitions that read those globals are created at run
;;; time with define-proc!, so a test does not depend on load order.
;;; ---------------------------------------------------------------------------

(defvar globals-test-boolean :boolean true)
(defvar globals-test-counter :i64 7)
(defvar globals-test-integer :i32 11)
(defvar globals-test-float :f64 1.5)
(defvar globals-test-float32 :f32 2.5)
(defvar globals-test-pointer :ptr nil)

;; A defproc wrapper for the print-from-a-background-thread test.
(defproc globals-test-background-printer [] :i64
  (let [turns :i64 0]
    (while globals-test-boolean
      (set turns (+ turns 1)))
    (println "background turns = " turns)
    turns))

(deftest test-globals-registry
  (testing "defvar registers a typed global and binds a handle"
    (is (= :boolean (globals/global-type "clodin.compiler/globals-test-boolean")))
    (is (= :i64 (globals/global-type "clodin.compiler/globals-test-counter")))
    (is (= :ptr (globals/global-type "clodin.compiler/globals-test-pointer")))
    (is (instance? clodin.globals.Global globals-test-counter)))

  (testing "the handle implements the interfaces reset! and swap! need"
    (is (instance? clojure.lang.IDeref globals-test-counter))
    (is (instance? clojure.lang.IAtom globals-test-counter))
    (is (instance? clojure.lang.IAtom2 globals-test-counter)))

  (testing ":boolean storage uses a byte-sized integer slot"
    (is (= :i32 (globals/storage-type :boolean)))
    (is (= :i64 (globals/storage-type :i64)))
    (is (= 4 (globals/storage-size :boolean)))
    (is (= 8 (globals/storage-size :i64))))

  (testing "names with $ collide with generated symbols"
    (is (thrown? clojure.lang.ExceptionInfo
                 (globals/register! "test" "bad$name" :i64 0))))

  (testing "unknown types and bad initial values are rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (globals/register! "test" "bad-type" :i8 0)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (globals/register! "test" "bad-value" :i64 "nope")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (globals/register! "test" "bad-pointer" :ptr 42)))))

(deftest test-globals-jvm-access
  (try
    (reset! globals-test-counter 7)
    (is (= 7 @globals-test-counter))

    (testing "reset! returns the new value"
      (is (= 100 (reset! globals-test-counter 100)))
      (is (= 100 @globals-test-counter)))

    (testing "swap! applies a function to the current value"
      (is (= 105 (swap! globals-test-counter + 5)))
      (is (= 210 (swap! globals-test-counter * 2)))
      (is (= 210 @globals-test-counter)))

    (testing "compare-and-set! compares the raw storage representation"
      (is (true? (compare-and-set! globals-test-counter 210 300)))
      (is (false? (compare-and-set! globals-test-counter 210 400)))
      (is (= 300 @globals-test-counter)))

    (testing "reset-vals! and swap-vals! return [old new]"
      (is (= [300 1] (reset-vals! globals-test-counter 1)))
      (is (= [1 2] (swap-vals! globals-test-counter inc)))
      (is (= 2 @globals-test-counter)))

    (testing "a :boolean global derefs to a Boolean and swaps with not"
      (reset! globals-test-boolean true)
      (is (true? @globals-test-boolean))
      (is (false? (swap! globals-test-boolean not)))
      (is (false? @globals-test-boolean))
      (is (true? (compare-and-set! globals-test-boolean false true))))

    (testing "float, i32 and pointer globals round-trip"
      (reset! globals-test-float 2.25)
      (is (= 2.25 @globals-test-float))
      (reset! globals-test-float32 3.5)
      (is (= 3.5 @globals-test-float32))
      (reset! globals-test-integer 70000)
      (is (= 70000 @globals-test-integer)))

    (testing "a :ptr global accepts a segment and derefs back to one"
      (let [arena (Arena/ofShared)
            buffer (.allocate arena 8)]
        (try
          (reset! globals-test-pointer buffer)
          (is (= (.address buffer) (.address ^MemorySegment @globals-test-pointer)))
          (reset! globals-test-pointer nil)
          (is (nil? @globals-test-pointer))
          (finally
            (.close arena)))))
    (finally
      (reset! globals-test-counter 7)
      (reset! globals-test-boolean true))))

(deftest test-globals-native-access
  (try
    (reset! globals-test-counter 7)
    (reset! globals-test-boolean true)
    (define-proc! "clodin.compiler" "globals-test-read-counter" [] []
                  '[(+ globals-test-counter 1)] nil false)
    (define-proc! "clodin.compiler" "globals-test-write-counter" ['value] [:i64]
                  '[(set globals-test-counter value)] nil false)
    (define-proc! "clodin.compiler" "globals-test-read-boolean" [] []
                  '[globals-test-boolean] nil false)
    (define-proc! "clodin.compiler" "globals-test-clear-boolean" [] []
                  '[(set globals-test-boolean false)] nil false)

    (testing "native code reads a global written by Clojure"
      (is (= 8 (invoke-procedure "globals-test-read-counter" [])))
      (reset! globals-test-counter 40)
      (is (= 41 (invoke-procedure "globals-test-read-counter" []))))

    (testing "a native set is visible to Clojure"
      (invoke-procedure "globals-test-write-counter" [99])
      (is (= 99 @globals-test-counter)))

    (testing "a :boolean global crosses the boundary as a boolean"
      (is (true? (invoke-procedure "globals-test-read-boolean" [])))
      (invoke-procedure "globals-test-clear-boolean" [])
      (is (false? @globals-test-boolean))
      (is (false? (invoke-procedure "globals-test-read-boolean" []))))
    (finally
      (reset! globals-test-counter 7)
      (reset! globals-test-boolean true))))

(deftest test-background-print-goes-to-conveyed-out
  (testing "a loop started in a future prints to the future's *out*"
    (reset! globals-test-boolean true)
    (let [output (java.io.StringWriter.)
          worker (binding [*out* output]
                   (future (globals-test-background-printer)))]
      (try
        (Thread/sleep 100)
        (is (not (realized? worker)))
        (reset! globals-test-boolean false)
        (is (integer? (deref worker 20000 ::timeout)))
        (is (re-find #"background turns = [0-9]+" (str output)))
        (finally
          (reset! globals-test-boolean false))))))

(deftest test-globals-native-loop-sees-reset
  (let [previous-level (current-optimization-level)]
    (try
      ;; Optimization is the point of this test: a plain, non-atomic load of a
      ;; loop invariant is hoisted out of the loop at every level, so an
      ;; optimized build only terminates because the load is atomic.
      (set-optimization-level! :O3)
      (reset! globals-test-boolean true)
      (define-proc! "clodin.compiler" "globals-test-spin" [] []
                    '[(let [turns :i64 0]
                        (while globals-test-boolean
                          (set turns (+ turns 1)))
                        turns)]
                    :i64 false)
      (let [worker (future (invoke-procedure "globals-test-spin" []))]
        (try
          (Thread/sleep 100)
          (is (not (realized? worker)))
          (reset! globals-test-boolean false)
          (is (integer? (deref worker 20000 ::timeout)))
          (finally
            (reset! globals-test-boolean true))))
      (finally
        (reset! globals-test-boolean true)
        (set-optimization-level! previous-level)))))

(deftest test-globals-reloadable-preserves-value
  (try
    (set-build-mode! :reloadable)
    (reset! globals-test-counter 0)
    (define-proc! "clodin.compiler" "globals-test-bump" [] []
                  '[(let [next-value :i64 (+ globals-test-counter 1)]
                      (set globals-test-counter next-value)
                      next-value)]
                  :i64 false)
    (is (= 1 (invoke-procedure "globals-test-bump" [])))
    (is (= 2 (invoke-procedure "globals-test-bump" [])))
    (testing "a procedure redefinition keeps the live global value"
      (define-proc! "clodin.compiler" "globals-test-bump" [] []
                    '[(let [next-value :i64 (+ globals-test-counter 10)]
                        (set globals-test-counter next-value)
                        next-value)]
                    :i64 false)
      (is (= 12 (invoke-procedure "globals-test-bump" [])))
      (is (= 12 @globals-test-counter)))
    (finally
      (reset! globals-test-counter 7)
      (set-build-mode! :monolithic))))

(deftest test-globals-reset-reinitialises
  (try
    (define-proc! "clodin.compiler" "globals-test-reading" [] []
                  '[globals-test-counter] nil false)
    ;; A monolithic build replaces the whole JIT and links fresh storage, so a
    ;; global does not survive a rebuild in that mode. This is the documented
    ;; trade-off: stateful programs use :reloadable mode.
    (is (= 7 (invoke-procedure "globals-test-reading" [])))
    (reset! globals-test-counter 10)
    (reset! globals-test-boolean false)
    (is (= 10 (invoke-procedure "globals-test-reading" [])))
    (reset-module!)
    (testing "a full reset restores the source initial values"
      (is (= 7 @globals-test-counter))
      (is (true? @globals-test-boolean)))
    (testing "re-emitted native code reads the rebuilt storage"
      (is (= 7 (invoke-procedure "globals-test-reading" []))))
    (finally
      (reset! globals-test-counter 7))))

(deftest test-globals-type-change
  (try
    (set-build-mode! :reloadable)
    (define-var! "clodin.compiler" "globals-test-retyped" :i64 5)
    (define-proc! "clodin.compiler" "globals-test-retyped-reader" [] []
                  '[globals-test-retyped] nil false)
    (is (= 5 (invoke-procedure "globals-test-retyped-reader" [])))
    (testing "a type change mints a new native symbol"
      (let [[entry changed?] (globals/register! "clodin.compiler"
                                                "globals-test-retyped" :f64 1.5)]
        (is (true? changed?))
        (is (= "clodin.compiler.globals-test-retyped$g1" (:symbol entry)))
        (is (= :f64 (:type entry)))))
    (testing "the re-emitted body reads the new layout"
      (define-proc! "clodin.compiler" "globals-test-retyped-reader" [] []
                    '[(+ globals-test-retyped 0.5)] nil false)
      (is (= 2.0 (invoke-procedure "globals-test-retyped-reader" []))))
    (finally
      (swap! globals/registry dissoc "globals-test-retyped")
      (swap! procedure-registry dissoc "globals-test-retyped-reader")
      (swap! procedure-handles dissoc "globals-test-retyped-reader")
      (set-build-mode! :monolithic))))

(deftest test-globals-in-standalone-build
  (let [output (str (System/getProperty "java.io.tmpdir")
                    "/clodin-globals-test-" (System/nanoTime))
        package "clodin_globals_aot_test"
        file (java.io.File. (str (System/getProperty "java.io.tmpdir")
                                 "/" package ".clj"))
        source (str
                "(ns " package "\n"
                "  (:require [clodin.compiler :refer [defproc defvar extern]]))\n\n"
                "(extern printf [:ptr] :i32 :varargs)\n\n"
                "(defvar aot-counter :i64 41)\n\n"
                "(defproc main [argc :i32 argv :ptr] :i32\n"
                "  (printf \"counter = %ld\\n\" aot-counter)\n"
                "  (set aot-counter (+ aot-counter 1))\n"
                "  (printf \"counter = %ld\\n\" aot-counter)\n"
                "  (to-i32 0))\n")]
    (try
      (spit file source)
      (load-file (.getAbsolutePath file))
      (build-executable! (symbol package) {:output output})
      (testing "a standalone binary links the storage of the globals it uses"
        (let [{:keys [out exit]} (shell/sh output)]
          (is (zero? exit))
          (is (= "counter = 41\ncounter = 42\n" out))))
      (finally
        (io/delete-file file true)
        (io/delete-file output true)))))

;;; ---------------------------------------------------------------------------
;;; Stack scratch allocation tests
;;; ---------------------------------------------------------------------------

(defproc stack-scratch-round-trip [value :i64]
  (let [scratch :ptr (stack-allocate 8 8)]
    (store :i64 scratch value)
    (load :i64 scratch)))

;; 5 bytes at alignment 16, so the byte count and the alignment differ. The
;; address is handed back through caller owned memory because the block itself
;; dies with the call.
(defproc stack-scratch-record-address [addresses :ptr]
  (buffer-set addresses :ptr 0 (stack-allocate 5 16)))

;; A stack-allocate inside a loop is hoisted to the entry block, so it is one
;; block reused on every iteration instead of a new one per iteration.
(defproc stack-scratch-record-addresses [addresses :ptr count :i64]
  (let [index :i64 0]
    (while (< index count)
      (buffer-set addresses :ptr index (stack-allocate 32 16))
      (set index (+ index 1)))))

;; The print helper shape: a stack block instead of malloc.
(defproc stack-scratch-format [value :i64]
  (let [scratch :ptr (stack-allocate 32 16)]
    (sprintf scratch "value=%ld" value)))

;; No declared return type, so the :ptr comes from expression-type. The block
;; itself is dead once the call returns, so only its type is checked.
(defproc stack-scratch-returned-address []
  (stack-allocate 8 8))

(deftest test-stack-allocate
  (testing "the block is a :ptr that load and store accept"
    (is (= 42 (stack-scratch-round-trip 42)))
    (is (= -1 (stack-scratch-round-trip -1))))

  (testing "the alloca carries the requested byte count and alignment"
    (let [ir (requiring-resolve 'clodin.inspect/llvm-ir)]
      (is (string/includes? (ir "stack-scratch-record-address")
                            "alloca [5 x i8], align 16"))))

  (testing "the returned address is aligned to the requested boundary"
    (let [arena (Arena/ofShared)
          addresses (.allocate arena 8)]
      (try
        (stack-scratch-record-address addresses)
        (is (zero? (mod (.address ^MemorySegment
                                   (.getAtIndex addresses ValueLayout/ADDRESS 0))
                          16)))
        (finally
          (.close arena)))))

  (testing "a stack-allocate inside a while is one block, not one per iteration"
    (let [arena (Arena/ofShared)
          address-count 4
          addresses (.allocate arena (* 8 address-count))]
      (try
        (stack-scratch-record-addresses addresses address-count)
        (let [collected (mapv (fn [index]
                                (.address ^MemorySegment
                                          (.getAtIndex addresses ValueLayout/ADDRESS index)))
                              (range address-count))]
          (is (apply = collected)))
        (finally
          (.close arena)))))

  (testing "formatting into stack scratch needs no malloc"
    (is (= 11 (stack-scratch-format 12345))))

  (testing "the form has type :ptr, so it drives return type inference"
    (is (instance? MemorySegment (stack-scratch-returned-address))))

  (testing "both arguments are validated at compile time"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at least one byte"
                          (define-proc! "clodin.compiler" "stack-allocate-empty" [] []
                                        '[(stack-allocate 0 16)] nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"power of two"
                          (define-proc! "clodin.compiler" "stack-allocate-odd-alignment" [] []
                                        '[(stack-allocate 16 3)] nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"literal non-negative integer byte-count"
                          (define-proc! "clodin.compiler" "stack-allocate-runtime-size"
                                        ['byte-count] [:i64]
                                        '[(stack-allocate byte-count 16)] nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at most 65536"
                          (define-proc! "clodin.compiler" "stack-allocate-huge-alignment"
                                        [] []
                                        '[(stack-allocate 16 131072)] nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expects .stack-allocate byte-count"
                          (define-proc! "clodin.compiler" "stack-allocate-arity" [] []
                                        '[(stack-allocate 16)] nil false))))

  (testing "a procedure may shadow a compiler form, but stays qualified only"
    (let [namespace "clodin.compiler.shadow"
          warnings (with-out-str
                     (binding [*err* *out*]
                       (defproc-form namespace 'stack-allocate []
                                     '[(to-i32 0)])))]
      (try
        (is (string/includes? warnings "shadows the compiler form"))
        (is (= :i32 (:return-type (get @procedure-registry
                                       "clodin.compiler.shadow/stack-allocate"))))
        ;; An unqualified name still means the form, so the body below compiles
        ;; a stack block rather than calling the shadowing procedure.
        (define-proc! "clodin.compiler.shadow" "unqualified-is-the-form" [] []
                      '[(stack-allocate 8 8)] nil false)
        (is (= :ptr (:return-type (get @procedure-registry
                                       "clodin.compiler.shadow/unqualified-is-the-form"))))
        ;; The qualified name reaches the procedure.
        (define-proc! "clodin.compiler.shadow" "qualified-is-the-procedure" [] []
                      '[(clodin.compiler.shadow/stack-allocate)] nil false)
        (is (= :i32 (:return-type (get @procedure-registry
                                       "clodin.compiler.shadow/qualified-is-the-procedure"))))
        (finally
          (doseq [name ["clodin.compiler.shadow/stack-allocate"
                        "clodin.compiler.shadow/unqualified-is-the-form"
                        "clodin.compiler.shadow/qualified-is-the-procedure"]]
            (swap! procedure-registry dissoc name)
            (swap! procedure-handles dissoc name)))))))

;;; ---------------------------------------------------------------------------
;;; is-null and pointer-call tests
;;; ---------------------------------------------------------------------------

(defproc is-null-pointer [pointer :ptr]
  (if (is-null pointer) 1 0))

;; strlen is a libc symbol, so this covers a real C function address reached
;; through a :ptr rather than through a declared extern.
(defproc pointer-call-strlen [strlen :ptr]
  (pointer-call strlen [:ptr] :i64 "hello"))

;; No declared return type: :i64 comes from the pointer-call signature.
(defproc pointer-call-inferred [strlen :ptr]
  (pointer-call strlen [:ptr] :i64 "hello"))

;; The shape the print runtime needs: the writer pointer may be absent.
(defproc pointer-call-if-null [callback :ptr value :i64]
  (if (is-null callback)
    -1
    (pointer-call callback [:i64] :i64 value)))

;; A void signature produces no value.
(defproc pointer-call-void-callback [callback :ptr value :i64]
  (pointer-call callback [:i64] :void value))

(defn- libc-symbol
  "Resolves a name in the default lookup, which covers the C library, and
  returns its address as a :ptr, so a test can hand clodin a real code pointer."
  [name]
  (let [lookup (.defaultLookup (Linker/nativeLinker))]
    (.orElseThrow (.find lookup name))))

(defn- upcall-stub
  "Builds a JVM upcall stub with the given FunctionDescriptor that invokes
  handler, and returns its segment. This is how a test hands native code an
  address that re-enters the JVM."
  [^Arena arena ^FunctionDescriptor descriptor handler]
  (let [method-type (.toMethodType descriptor)
        argument-count (.parameterCount method-type)
        invoke-type (MethodType/methodType
                     Object (into-array Class (repeat argument-count Object)))
        handle (-> (.findVirtual (MethodHandles/publicLookup)
                                 clojure.lang.IFn "invoke" invoke-type)
                   (.bindTo handler)
                   (.asType method-type))]
    (clojure.lang.Reflector/invokeInstanceMethod
     (Linker/nativeLinker) "upcallStub"
     (to-array [handle descriptor arena (make-array Linker$Option 0)]))))

(defn- record-i64-upcall
  "A void(i64) upcall that records its argument."
  [^Arena arena recorded]
  (upcall-stub arena
               (FunctionDescriptor/ofVoid
                (into-array MemoryLayout [ValueLayout/JAVA_LONG]))
               (fn [value] (reset! recorded value))))

(defn- echo-i64-upcall
  "An i64(i64) upcall that returns its argument."
  [^Arena arena]
  (upcall-stub arena
               (FunctionDescriptor/of ValueLayout/JAVA_LONG
                                      (into-array MemoryLayout [ValueLayout/JAVA_LONG]))
               (fn [value] value)))

(deftest test-is-null
  (testing "is-null is a procedure in clodin.core, not a compiler form"
    (is (contains? @procedure-registry "clodin.core/is-null")))
  (testing "a :ptr holding address zero is null and any other address is not"
    (let [arena (Arena/ofShared)]
      (try
        (is (= 1 (is-null-pointer nil)))
        (is (= 1 (is-null-pointer (MemorySegment/ofAddress 0))))
        (is (= 0 (is-null-pointer (.allocate arena 8))))
        (finally
          (.close arena)))))

  (testing "is-null rejects a type that cannot hold a null"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not match parameters"
                          (define-proc! "clodin.compiler" "is-null-non-pointer" [] []
                                        '[(if (is-null 5) 1 0)] :i64 false))))

  (testing "is-null expects exactly one operand"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expects 1 arguments"
                          (define-proc! "clodin.compiler" "is-null-arity" [] []
                                        '[(if (is-null) 1 0)] :i64 false)))))

(deftest test-pointer-call
  (testing "a pointer-call reaches a libc function address"
    (is (= 5 (pointer-call-strlen (libc-symbol "strlen")))))

  (testing "an argument and return type written at the call site are used"
    (is (= :i64 (:return-type (get @procedure-registry
                                   "clodin.compiler/pointer-call-inferred")))))

  (testing "a void signature produces no value"
    (let [arena (Arena/ofShared)
          recorded (atom nil)]
      (try
        (is (nil? (pointer-call-void-callback (record-i64-upcall arena recorded) 42)))
        (is (= 42 @recorded))
        (finally
          (.close arena)))))

  (testing "a pointer-call reaches a JVM upcall stub"
    (let [arena (Arena/ofShared)]
      (try
        (is (= 7 (pointer-call-if-null (echo-i64-upcall arena) 7)))
        (finally
          (.close arena)))))

  (testing "a null pointer is the caller's problem, tested with is-null"
    (is (= -1 (pointer-call-if-null nil 7))))

  (testing "the written signature is checked as far as it can be"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expects a :ptr function address"
                          (define-proc! "clodin.compiler" "pointer-call-non-pointer" [] []
                                        '[(pointer-call 5 [] :void)] nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes 1 arguments, got 0"
                          (define-proc! "clodin.compiler" "pointer-call-arity" ['callback] [:ptr]
                                        '[(pointer-call callback [:i64] :void)] nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not match pointer-call parameters"
                          (define-proc! "clodin.compiler" "pointer-call-argument-types"
                                        ['callback] [:ptr]
                                        '[(pointer-call callback [:i64] :void 1.5)]
                                        nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal vector of parameter types"
                          (define-proc! "clodin.compiler" "pointer-call-non-literal"
                                        ['callback] [:ptr]
                                        '[(pointer-call callback :i64 :void)]
                                        nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"parameter types are"
                          (define-proc! "clodin.compiler" "pointer-call-void-parameter"
                                        ['callback] [:ptr]
                                        '[(pointer-call callback [:void] :void)]
                                        nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"return type is"
                          (define-proc! "clodin.compiler" "pointer-call-silly-return"
                                        ['callback] [:ptr]
                                        '[(pointer-call callback [] :i8)]
                                        nil false))))

  (testing "a void call has no value to bind"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"its value is no value"
                          (define-proc! "clodin.compiler" "pointer-call-void-value"
                                        ['callback] [:ptr]
                                        '[(let [value :i64 (pointer-call callback [] :void)]
                                            value)]
                                        nil false)))))

;;; ---------------------------------------------------------------------------
;;; Statement position and :void return types
;;; ---------------------------------------------------------------------------

;; A value producing procedure, so an arm can have both a side effect and a
;; return value that differs from the other arm's.
(defproc store-and-return [pointer :ptr value :i64]
  (store :i64 pointer value)
  value)

(defproc store-only [pointer :ptr value :i64] :void
  (store :i64 pointer value))

;; The print runtime shape: one arm returns :i64, the other returns nothing.
;; In value position this is an error; as a statement it is legal.
(defproc statement-if-mixed-arms [pointer :ptr flag :boolean] :void
  (if flag
    (store-only pointer 1)
    (store-and-return pointer 2)))

;; The same mismatch in a non-final body form, where the value is discarded.
(defproc statement-if-nonfinal [pointer :ptr flag :boolean]
  (if flag
    (store-only pointer 3)
    (store-and-return pointer 4))
  (load :i64 pointer))

;; A let in statement position forwards statement position to its last form.
(defproc statement-let [pointer :ptr flag :boolean]
  (let [ignored :i64 0]
    (if flag
      (store-only pointer 5)
      (store-and-return pointer 6)))
  (load :i64 pointer))

;; A when body discards its last form's value too.
(defproc statement-when [pointer :ptr flag :boolean]
  (when flag
    (if flag
      (store-only pointer 7)
      (store-and-return pointer 8)))
  (load :i64 pointer))

;; A while body discards its last form's value on every iteration.
(defproc statement-while [pointer :ptr count :i64]
  (let [index :i64 0]
    (while (< index count)
      (set index (+ index 1))
      (if (= index 0)
        (store-only pointer 9)
        (store-and-return pointer 10)))
    (load :i64 pointer)))

;; A declared :void discards whatever the body produces.
(defproc void-value-body [] :void
  42)

(deftest test-statement-position
  (testing "a statement if runs one arm and discards both values"
    (let [arena (Arena/ofShared)
          pointer (.allocate arena 8)]
      (try
        (is (nil? (statement-if-mixed-arms pointer true)))
        (is (= 1 (.getAtIndex pointer ValueLayout/JAVA_LONG 0)))
        (statement-if-mixed-arms pointer false)
        (is (= 2 (.getAtIndex pointer ValueLayout/JAVA_LONG 0)))
        (finally
          (.close arena)))))

  (testing "the same mismatch is legal as a non-final body form"
    (let [arena (Arena/ofShared)
          pointer (.allocate arena 8)]
      (try
        (is (= 3 (statement-if-nonfinal pointer true)))
        (is (= 4 (statement-if-nonfinal pointer false)))
        (finally
          (.close arena)))))

  (testing "statement position reaches a let body's last form"
    (let [arena (Arena/ofShared)
          pointer (.allocate arena 8)]
      (try
        (is (= 5 (statement-let pointer true)))
        (is (= 6 (statement-let pointer false)))
        (finally
          (.close arena)))))

  (testing "statement position reaches a when body's last form"
    (let [arena (Arena/ofShared)
          pointer (.allocate arena 8)]
      (try
        (is (= 7 (statement-when pointer true)))
        ;; The untaken body must not write, so a sentinel survives.
        (.set pointer ValueLayout/JAVA_LONG 0 0)
        (is (= 0 (statement-when pointer false)))
        (finally
          (.close arena)))))

  (testing "statement position reaches a while body's last form"
    (let [arena (Arena/ofShared)
          pointer (.allocate arena 8)]
      (try
        (is (= 10 (statement-while pointer 3)))
        (.set pointer ValueLayout/JAVA_LONG 0 0)
        (is (= 0 (statement-while pointer 0)))
        (finally
          (.close arena)))))

  (testing "a statement if builds no phi"
    (let [ir (requiring-resolve 'clodin.inspect/llvm-ir)]
      (is (not (string/includes? (ir "statement-if-mixed-arms") "phi")))))

  (testing "value position still requires both arms to agree"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"arms must both produce a value of the same type"
         (define-proc! "clodin.compiler" "statement-if-value-position" ['pointer] [:ptr]
                       '[(if true 1 (store :i64 pointer 0))] :i64 false)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"arms must both produce a value of the same type"
         (define-proc! "clodin.compiler" "statement-if-let-value" ['pointer] [:ptr]
                       '[(let [value :i64 (if true 1 (store :i64 pointer 0))] value)]
                       nil false)))))

(deftest test-void-return-type
  (testing ":void is a declarable return type and the call produces no value"
    (is (nil? (void-value-body)))
    (is (nil? (store-only (.allocate (Arena/ofShared) 8) 1))))

  (testing "the registry keeps the declaration but reports a void ABI"
    (let [entry (get @procedure-registry "clodin.compiler/void-value-body")]
      (is (nil? (:return-type entry)))
      (is (= :void (:explicit-return-type entry)))))

  (testing "a declared :void body is a statement, so it has no value to return"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no value to return"
                          (define-proc! "clodin.compiler" "void-declared-i64" ['pointer] [:ptr]
                                        '[(if true (store :i64 pointer 1)
                                           (store :i64 pointer 2))]
                                        :i64 false))))

  (testing ":void is rejected as a parameter type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot take a :void parameter"
                          (define-proc! "clodin.compiler" "void-parameter" ['value] [:void]
                                        '[1] nil false))))

  (testing "an unsupported declared return type is a clear error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported return type"
                          (define-proc! "clodin.compiler" "unsupported-return" [] []
                                        '[1] :i8 false)))))

;;; ---------------------------------------------------------------------------
;;; Qualified names
;;; ---------------------------------------------------------------------------

(deftest test-qualified-names
  (try
    (testing "a procedure registers under its qualified name and symbol"
      (define-proc! "examples.naming" "helper" [] [] '[41] :i64 false)
      (let [entry (get @procedure-registry "examples.naming/helper")]
        (is (some? entry))
        (is (= "helper" (:name entry)))
        (is (= "examples.naming" (:namespace entry)))
        (is (= "examples.naming.helper" (:body-symbol entry)))))

    (testing "its own namespace reaches it unqualified"
      (define-proc! "examples.naming" "own-caller" [] []
                    '[(helper)] :i64 false)
      (is (= :i64 (:return-type (get @procedure-registry
                                     "examples.naming/own-caller")))))

    (testing "another namespace reaches it by qualified name"
      (define-proc! "examples.naming" "qualified-caller" [] []
                    '[(examples.naming/helper)] :i64 false)
      (is (= :i64 (:return-type (get @procedure-registry
                                     "examples.naming/qualified-caller")))))

    (testing "an unqualified name is not visible from another namespace"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown clodin procedure: helper"
                            (define-proc! "examples.other" "caller" [] []
                                          '[(helper)] :i64 false))))

    (testing "two namespaces can define the same short name"
      (define-proc! "examples.other" "helper" [] [] '[42] :i64 false)
      (is (= "examples.other.helper"
             (:body-symbol (get @procedure-registry "examples.other/helper"))))
      (is (= "examples.naming.helper"
             (:body-symbol (get @procedure-registry "examples.naming/helper")))))

    (testing "a core procedure is visible unqualified everywhere"
      (define-proc! "clodin.core" "core-helper" [] [] '[7] :i64 false)
      (define-proc! "examples.other" "core-caller" [] []
                    '[(core-helper)] :i64 false)
      (is (= :i64 (:return-type (get @procedure-registry
                                     "examples.other/core-caller")))))

    (testing "its own namespace shadows core"
      (define-proc! "clodin.core" "shadowed-helper" [] [] '[1] :i64 false)
      (define-proc! "examples.naming" "shadowed-helper" [] [] '[2] :i64 false)
      (define-proc! "examples.naming" "shadow-caller" [] []
                    '[(shadowed-helper)] :i64 false)
      (let [callees (:callees (get @procedure-registry
                                   "examples.naming/shadow-caller"))]
        (is (contains? callees "examples.naming/shadowed-helper"))
        (is (not (contains? callees "clodin.core/shadowed-helper")))))

    (testing "a global is qualified the same way"
      (define-var! "examples.naming" "counter" :i64 5)
      (is (= "examples.naming.counter"
             (:symbol (globals/entry "examples.naming/counter"))))
      (define-proc! "examples.naming" "reads-own-global" [] []
                    '[(+ counter 1)] :i64 false)
      (define-proc! "examples.naming" "reads-qualified-global" [] []
                    '[(+ examples.naming/counter 1)] :i64 false)
      (is (= #{"examples.naming.counter"}
             (:globals (get @procedure-registry
                            "examples.naming/reads-own-global"))))
      (is (= #{"examples.naming.counter"}
             (:globals (get @procedure-registry
                            "examples.naming/reads-qualified-global"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown symbol: counter"
                            (define-proc! "examples.other" "reads-naming-global" [] []
                                          '[(+ counter 1)] :i64 false))))

    (finally
      (doseq [name ["examples.naming/helper" "examples.naming/own-caller"
                    "examples.naming/qualified-caller"
                    "examples.naming/shadowed-helper"
                    "examples.naming/shadow-caller"
                    "examples.naming/reads-own-global"
                    "examples.naming/reads-qualified-global"
                    "examples.other/helper" "examples.other/core-caller"
                    "clodin.core/core-helper" "clodin.core/shadowed-helper"]]
        (swap! procedure-registry dissoc name)
        (swap! procedure-handles dissoc name))
      (swap! globals/registry dissoc "examples.naming/counter"))))

;;; ---------------------------------------------------------------------------
;;; Print runtime in clodin
;;; ---------------------------------------------------------------------------

(def ^:private writer-global "clodin.runtime/clodin-write")

(defn- writer-address
  "The address the writer global currently holds, or nil when it is null."
  []
  (some-> ^MemorySegment (globals/read-value writer-global) (.address)))

(defproc runtime-print-caller [value :i64]
  (println "value = " value))

(deftest test-runtime-writer
  (testing "a build installs the writer upcall address"
    (is (= (print/writer-address) (writer-address))))

  (testing "a procedure that prints records the runtime as callees"
    (let [callees (:callees (get @procedure-registry
                                 "clodin.compiler/runtime-print-caller"))]
      (is (contains? callees "clodin.runtime/print-bytes"))
      (is (contains? callees "clodin.runtime/print-i64"))
      (is (contains? callees "clodin.runtime/print-newline"))))

  (testing "the compiler loads the runtime before a user body is compiled"
    (is (contains? @procedure-registry "clodin.runtime/write-to-out"))
    (is (some? (globals/entry writer-global))))

  (testing "a reset installs it again after it was cleared"
    (globals/write-value! writer-global 0)
    (is (nil? (writer-address)))
    (reset-module!)
    (is (= (print/writer-address) (writer-address))))

  (testing "a monolithic rebuild installs it again after it was cleared"
    (try
      (set-build-mode! :monolithic)
      (reset-module!)
      (globals/write-value! writer-global 0)
      (is (nil? (writer-address)))
      (reset! module-dirty true)
      (compile-module!)
      (is (= (print/writer-address) (writer-address)))
      (finally
        (set-build-mode! :monolithic)))))

(deftest test-runtime-writer-does-not-recompile
  (testing "installing the writer does not trigger a second build"
    (let [original (var-get #'recompile-module!)
          builds (atom 0)]
      (with-redefs [recompile-module! (fn []
                                        (swap! builds inc)
                                        (original))]
        (reset! module-dirty true)
        (compile-module!))
      (is (= 1 @builds)))))

(deftest test-runtime-in-standalone-build
  (testing "an offline binary prints through write(1, ...) with no JVM"
    (let [output (str (System/getProperty "java.io.tmpdir")
                      "/clodin-runtime-test-" (System/nanoTime))
          package "clodin_runtime_aot_test"
          file (java.io.File. (str (System/getProperty "java.io.tmpdir")
                                   "/" package ".clj"))
          source (str
                  "(ns " package "\n"
                  "  (:require [clodin.compiler :refer [defproc]]))\n\n"
                  "(defproc main [argc :i32 argv :ptr] :i32\n"
                  "  (println \"value = \" 42)\n"
                  "  (print \"done\")\n"
                  "  (println)\n"
                  "  (to-i32 0))\n")]
      (try
        (spit file source)
        (load-file (.getAbsolutePath file))
        (build-executable! (symbol package) {:output output})
        (let [{:keys [out exit]} (shell/sh output)]
          (is (zero? exit))
          (is (= "value = 42\ndone\n" out)))
        (finally
          (io/delete-file file true)
          (io/delete-file output true))))))

(defn -main
  [& _]
  (let [summary (run-tests 'clodin.compiler)]
    (when (or (pos? (:fail summary 0)) (pos? (:error summary 0)))
      (System/exit 1))))
