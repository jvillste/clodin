(ns clodin.llvm-api
  "Clojure-friendly wrapper over the jextract-generated LLVM C API
  bindings (clodin.llvm.LLVM). All opaque LLVM values are represented as
  native MemorySegments whose addresses are passed straight to the C API.

  This namespace owns raw LLVM resource handling: creating a context,
  module and builder for a procedure, building instructions through the
  builder, and serialising the finished module to in-memory bitcode. It
  also owns the ORC/LLJIT pipeline that turns a linked module into
  callable machine code in the current process.

  Memory ownership follows the C API. Contexts, modules and builders are
  disposed explicitly. C strings and small output segments are allocated
  in an Arena supplied by the caller and live as long as that arena."
  (:require [clojure.string :as string])
  (:import (clodin.llvm LLVM)
           (java.lang.foreign Arena Linker Linker$Option FunctionDescriptor
                              MemoryLayout MemorySegment ValueLayout)))

;;; ---------------------------------------------------------------------------
;;; Low level helpers
;;; ---------------------------------------------------------------------------

(defn c-string
  "Returns a C string (const char*) containing value, owned by arena."
  ^MemorySegment [^Arena arena ^String value]
  (.allocateFrom arena value))

(defn null?
  "True when an opaque LLVM handle points at nothing. Opaque handles are
  pointer types, not LLVMValueRefs, so compare the native address directly
  instead of calling LLVMIsNull, which must only ever be given a ValueRef."
  [^MemorySegment handle]
  (or (nil? handle) (zero? (.address handle))))

(defn- throw-error
  [context message]
  (throw (ex-info message context)))

;;; ---------------------------------------------------------------------------
;;; Types
;;; ---------------------------------------------------------------------------

(defn type-for
  "Returns the LLVMTypeRef for a clodin type in the given context."
  ^MemorySegment [^MemorySegment context type]
  (case type
    :i64 (LLVM/LLVMInt64TypeInContext context)
    :i32 (LLVM/LLVMInt32TypeInContext context)
    :f64 (LLVM/LLVMDoubleTypeInContext context)
    :f32 (LLVM/LLVMFloatTypeInContext context)
    :ptr (LLVM/LLVMPointerTypeInContext context 0)
    (throw (ex-info (str "Unsupported clodin type: " type) {:type type}))))

(defn void-type
  ^MemorySegment [^MemorySegment context]
  (LLVM/LLVMVoidTypeInContext context))

(defn i1-type
  ^MemorySegment [^MemorySegment context]
  (LLVM/LLVMInt1TypeInContext context))

(defn i8-type
  ^MemorySegment [^MemorySegment context]
  (LLVM/LLVMInt8TypeInContext context))

(defn pointer-array
  "Allocates a native array of pointers in the arena, fills it with the given segments, and returns the array as a single MemorySegment (LLVM C pointer-array parameters are not Java arrays)."
  ^MemorySegment [^Arena arena segments]
  (let [length (count segments)
        allocate-count (if (zero? length) 1 length)
        layout ValueLayout/ADDRESS
        array (.allocate arena (* (long allocate-count) (.byteSize layout)))]
    (dotimes [index length]
      (.set array layout (* index (.byteSize layout)) (nth segments index)))
    array))

(defn function-type
  "Builds the LLVMFunctionType for a signature."
  ^MemorySegment [^MemorySegment context ^Arena arena return-type param-types]
  (let [return-t (if return-type
                   (type-for context return-type)
                   (void-type context))
        param-count (count param-types)]
    (LLVM/LLVMFunctionType
     return-t
     (pointer-array arena (map #(type-for context %) param-types))
     param-count
     0)))

;;; ---------------------------------------------------------------------------
;;; Context, module, builder lifecycle
;;; ---------------------------------------------------------------------------

(defn new-context
  ^MemorySegment []
  (LLVM/LLVMContextCreate))

(defn dispose-context
  [^MemorySegment context]
  (LLVM/LLVMContextDispose context))

(defn new-module
  ^MemorySegment [^MemorySegment context ^String name]
  (LLVM/LLVMModuleCreateWithNameInContext (c-string (Arena/global) name) context))

(defn dispose-module
  [^MemorySegment module]
  (LLVM/LLVMDisposeModule module))

(defn new-builder
  ^MemorySegment [^MemorySegment context]
  (LLVM/LLVMCreateBuilderInContext context))

(defn dispose-builder
  [^MemorySegment builder]
  (LLVM/LLVMDisposeBuilder builder))

(defn append-entry-block
  "Creates a basic block, appends it to the function, positions the builder
  at it, and returns the block."
  ^MemorySegment [^MemorySegment function ^MemorySegment builder ^MemorySegment context
                  ^Arena arena ^String name]
  (let [block (LLVM/LLVMCreateBasicBlockInContext context (c-string arena name))]
    (LLVM/LLVMAppendExistingBasicBlock function block)
    (LLVM/LLVMPositionBuilderAtEnd builder block)
    block))

(defn- first-instruction
  ^MemorySegment [^MemorySegment block]
  (LLVM/LLVMGetFirstInstruction block))

(defn- entry-basic-block
  ^MemorySegment [^MemorySegment function]
  (LLVM/LLVMGetEntryBasicBlock function))

;;; ---------------------------------------------------------------------------
;;; Constants
;;; ---------------------------------------------------------------------------

(defn const-int
  "A constant integer. Untyped integer literals are 64 bit."
  ^MemorySegment [^MemorySegment context long-value]
  (LLVM/LLVMConstInt (LLVM/LLVMInt64TypeInContext context) (long long-value) 0))

(defn const-int-of-type
  "A constant integer of the given clodin type."
  ^MemorySegment [^MemorySegment context type long-value]
  (LLVM/LLVMConstInt (type-for context type) (long long-value) 0))

(defn const-real
  "A constant 64 bit floating point value."
  ^MemorySegment [^MemorySegment context double-value]
  (LLVM/LLVMConstReal (LLVM/LLVMDoubleTypeInContext context) (double double-value)))

;;; ---------------------------------------------------------------------------
;;; Instruction builders (all insert at the builder's current position)
;;; ---------------------------------------------------------------------------

(defn- name-token
  "Returns a C string name. NULL names crash the panama downcall for a char* parameter, so always pass a real, possibly empty, string."
  ^MemorySegment [^MemorySegment builder ^Arena arena ^String name]
  (c-string arena (if (and name (not (empty? name))) name "")))

(defn build-alloca
  ^MemorySegment [^MemorySegment builder ^MemorySegment element-type ^Arena arena ^String name]
  (LLVM/LLVMBuildAlloca builder element-type (name-token builder arena name)))

(defn build-load
  ^MemorySegment [^MemorySegment builder ^MemorySegment element-type
                  ^MemorySegment pointer ^Arena arena ^String name]
  (LLVM/LLVMBuildLoad2 builder element-type pointer (name-token builder arena name)))

(defn build-store
  ^MemorySegment [^MemorySegment builder ^MemorySegment value ^MemorySegment pointer]
  (LLVM/LLVMBuildStore builder value pointer))

(defn- build-binary
  [build-fn builder lhs rhs name arena]
  (build-fn builder lhs rhs (name-token builder arena name)))

(defn build-int-add  [b l r n a] (build-binary LLVM/LLVMBuildAdd b l r n a))
(defn build-int-sub  [b l r n a] (build-binary LLVM/LLVMBuildSub b l r n a))
(defn build-int-mul  [b l r n a] (build-binary LLVM/LLVMBuildMul b l r n a))
(defn build-int-sdiv [b l r n a] (build-binary LLVM/LLVMBuildSDiv b l r n a))
(defn build-float-add [b l r n a] (build-binary LLVM/LLVMBuildFAdd b l r n a))
(defn build-float-sub [b l r n a] (build-binary LLVM/LLVMBuildFSub b l r n a))
(defn build-float-mul [b l r n a] (build-binary LLVM/LLVMBuildFMul b l r n a))
(defn build-float-div [b l r n a] (build-binary LLVM/LLVMBuildFDiv b l r n a))

(defn build-sitofp
  ^MemorySegment [^MemorySegment builder ^MemorySegment value
                  ^MemorySegment dest-type ^Arena arena ^String name]
  (LLVM/LLVMBuildSIToFP builder value dest-type (name-token builder arena name)))

(defn build-fptrunc
  "Truncates a wider floating point value (e.g. f64) to a narrower one
  (e.g. f32)."
  ^MemorySegment [^MemorySegment builder ^MemorySegment value
                  ^MemorySegment dest-type ^Arena arena ^String name]
  (LLVM/LLVMBuildFPTrunc builder value dest-type (name-token builder arena name)))

(defn build-inttoptr
  ^MemorySegment [^MemorySegment builder ^MemorySegment value
                  ^MemorySegment dest-type ^Arena arena ^String name]
  (LLVM/LLVMBuildIntToPtr builder value dest-type (name-token builder arena name)))

(defn- bytes-gep
  "Builds getelementptr with a byte offset, matching the text IR getelementptr inbounds i8, ptr P, T O."
  ^MemorySegment [^MemorySegment builder ^MemorySegment context ^MemorySegment pointer
                  ^MemorySegment offset ^Arena arena]
  (LLVM/LLVMBuildInBoundsGEP2
   builder
   (i8-type context)
   pointer
   (pointer-array arena [offset])
   1
   (name-token builder arena "")))

(defn build-bytes-gep
  ^MemorySegment [state ^MemorySegment pointer ^MemorySegment offset]
  (bytes-gep (:builder state) (:context state) pointer offset (:arena state)))

;;; --- comparisons -----------------------------------------------------------

(defn- int-predicate
  [operator]
  (case operator
    <  (LLVM/LLVMIntSLT)
    <= (LLVM/LLVMIntSLE)
    >  (LLVM/LLVMIntSGT)
    >= (LLVM/LLVMIntSGE)
    =  (LLVM/LLVMIntEQ)
    not= (LLVM/LLVMIntNE)))

(defn- real-predicate
  [operator]
  (case operator
    <  (LLVM/LLVMRealOLT)
    <= (LLVM/LLVMRealOLE)
    >  (LLVM/LLVMRealOGT)
    >= (LLVM/LLVMRealOGE)
    =  (LLVM/LLVMRealOEQ)
    not= (LLVM/LLVMRealUNE)))

(defn build-icmp
  ^MemorySegment [^MemorySegment builder operator lhs rhs arena]
  (LLVM/LLVMBuildICmp builder (int-predicate operator) lhs rhs (name-token builder arena "")))

(defn build-fcmp
  ^MemorySegment [^MemorySegment builder operator lhs rhs arena]
  (LLVM/LLVMBuildFCmp builder (real-predicate operator) lhs rhs (name-token builder arena "")))

(defn build-select
  "Builds an llvm.select instruction: condition ? then-value : else-value."
  ^MemorySegment [^MemorySegment builder ^MemorySegment condition
                  ^MemorySegment then-value ^MemorySegment else-value ^Arena arena]
  (LLVM/LLVMBuildSelect builder condition then-value else-value
                        (name-token builder arena "")))

;;; --- control flow -----------------------------------------------------------

(defn build-br
  ^MemorySegment [^MemorySegment builder ^MemorySegment dest]
  (LLVM/LLVMBuildBr builder dest))

(defn build-cond-br
  [^MemorySegment builder ^MemorySegment condition ^MemorySegment then-block ^MemorySegment else-block]
  (LLVM/LLVMBuildCondBr builder condition then-block else-block))

(defn build-ret
  [^MemorySegment builder ^MemorySegment value]
  (LLVM/LLVMBuildRet builder value))

(defn build-ret-void
  [^MemorySegment builder]
  (LLVM/LLVMBuildRetVoid builder))

(defn position-at-end
  [^MemorySegment builder ^MemorySegment block]
  (LLVM/LLVMPositionBuilderAtEnd builder block))

;;; --- calls -----------------------------------------------------------------

(defn get-or-add-function
  "Finds a function in the module by name, or declares it. Used for calls
  to other procedures."
  ^MemorySegment [^MemorySegment module ^MemorySegment name ^MemorySegment function-type]
  (let [existing (LLVM/LLVMGetNamedFunction module name)]
    (if (null? existing)
      (LLVM/LLVMAddFunction module name function-type)
      existing)))

(defn build-call
  "Builds a call instruction. Returns the result value, or nil for a void
  call."
  [^MemorySegment builder ^MemorySegment function-type ^MemorySegment callee
   argument-values ^Arena arena]
  (let [count (count argument-values)]
    (LLVM/LLVMBuildCall2
     builder function-type callee
     (pointer-array arena argument-values)
     count
     (name-token builder arena ""))))

(defn- type-suffix
  "The text suffix LLVM appends to the name of an overloaded intrinsic for a
  given clodin type, e.g. \"f64\" -> llvm.sqrt.f64."
  [type]
  (case type
    :f64 "f64"
    :f32 "f32"
    :i64 "i64"
    :i32 "i32"
    (throw (ex-info (str "No intrinsic suffix for type " type) {:type type}))))

(defn build-intrinsic-fcall
  "Declares a named LLVM intrinsic overloaded on argument-type (a clodin type
  keyword) and calls it with argument-values. The intrinsic is identified by
  its textual name, composed from prefix and the type suffix, e.g. prefix
  \"llvm.sqrt\" with :f64 becomes \"llvm.sqrt.f64\". Returns the result
  value; the result type equals argument-type. Intrinsics are lowered by the
  JIT so no external symbol needs to be resolved."
  [state prefix argument-type argument-values]
  (let [{:keys [context module builder arena]} state
        name (str prefix "." (type-suffix argument-type))
        param-types (vec (repeat (count argument-values) argument-type))
        function-type (function-type context arena argument-type param-types)
        callee (get-or-add-function module (c-string arena name) function-type)]
    (build-call builder function-type callee argument-values arena)))

;;; --- function parameters -----------------------------------------------------

(defn get-param
  ^MemorySegment [^MemorySegment function index]
  (LLVM/LLVMGetParam function (int index)))

(defn add-function
  "Adds a function to the module."
  ^MemorySegment [^MemorySegment module ^MemorySegment name ^MemorySegment function-type]
  (LLVM/LLVMAddFunction module name function-type))

(defn new-block
  "Creates an unattached basic block in the given context."
  ^MemorySegment [^MemorySegment context ^Arena arena ^String name]
  (LLVM/LLVMCreateBasicBlockInContext context (c-string arena name)))

(defn append-block
  "Attaches an existing basic block to the end of a function."
  [^MemorySegment function ^MemorySegment block]
  (LLVM/LLVMAppendExistingBasicBlock function block))

(defn position-before
  "Positions the builder so new instructions are inserted before an existing instruction."
  [^MemorySegment builder ^MemorySegment instruction]
  (LLVM/LLVMPositionBuilderBefore builder instruction))

(defn hoisted-alloca
  "Builds an alloca of element-type at the front of the entry block, then restores the
  builder to the current block and returns the alloca value; hoisting means a let inside
  a loop does not grow the stack on every iteration."
  [^MemorySegment builder ^MemorySegment context ^Arena arena
   ^MemorySegment entry-block ^MemorySegment current-block ^MemorySegment element-type]
  (let [first-instruction (first-instruction entry-block)]
    (if (null? first-instruction)
      (do (position-at-end builder entry-block)
          (let [alloca (LLVM/LLVMBuildAlloca builder element-type
                                             (name-token builder arena nil))]
            (position-at-end builder current-block)
            alloca))
      (do (position-before builder first-instruction)
          (let [alloca (LLVM/LLVMBuildAlloca builder element-type
                                             (name-token builder arena nil))]
            (position-at-end builder current-block)
            alloca)))))

;;; ---------------------------------------------------------------------------
;;; Serialisation : module -> bitcode
;;; ---------------------------------------------------------------------------

(defn write-bitcode
  "Serialises a module to an in-memory bitcode byte array."
  [^MemorySegment module]
  (let [buffer (LLVM/LLVMWriteBitcodeToMemoryBuffer module)
        size (int (LLVM/LLVMGetBufferSize buffer))
        start (LLVM/LLVMGetBufferStart buffer)
        bytes (byte-array size)]
    (try
      (let [view (.reinterpret start size)]
        (dotimes [index size]
          (aset-byte bytes index (.get view ValueLayout/JAVA_BYTE index))))
      (finally
        (LLVM/LLVMDisposeMemoryBuffer buffer)))
    bytes))

;;; ---------------------------------------------------------------------------
;;; Verification and linking
;;; ---------------------------------------------------------------------------

(defn verify
  "Verifies a module. Throws with the verifier message on failure."
  [^MemorySegment module ^Arena arena]
  (let [out (.allocate arena ValueLayout/ADDRESS)
        failed (LLVM/LLVMVerifyModule module (int 1) out)]
    (when-not (zero? failed)
      (let [message-pointer (.get out ValueLayout/ADDRESS 0)]
        (throw-error
         {:module module}
         (if (null? message-pointer)
           "LLVM module failed verification"
           (.getString (.reinterpret message-pointer 4096) 0)))))))

(defn- codegen-opt-level
  "Maps a clodin optimization level keyword to the LLVM CodeGenOpt::Level
  enum value (None=0, Less=1, Default=2, Aggressive=3)."
  [level]
  (case level
    :none 2
    :O0 0
    :O1 1
    :O2 2
    :O3 3
    (throw (ex-info (str "Unknown optimization level: " level) {:level level}))))

(defn- host-target-machine
  "Creates a TargetMachine for the host at the given LLVM codegen optimization
  level. Used to build the LLJIT target machine so that it optimises the code
  it emits. The caller must dispose it with LLVMDisposeTargetMachine."
  [^Arena arena codegen-level]
  (let [triple-ptr (LLVM/LLVMGetDefaultTargetTriple)
        triple (.getString triple-ptr 0)
        target-slot (.allocate arena ValueLayout/ADDRESS)
        error-slot (.allocate arena ValueLayout/ADDRESS)
        ok (LLVM/LLVMGetTargetFromTriple (c-string arena triple)
                                         target-slot error-slot)]
    (when-not (zero? ok)
      (throw-error {:triple triple} "unknown host target triple"))
    (LLVM/LLVMCreateTargetMachine
     (.get target-slot ValueLayout/ADDRESS 0)
     (c-string arena triple)
     (c-string arena "")  ; CPU (must be a real, possibly empty, string)
     (c-string arena "")  ; features
     codegen-level
     (int 0) ; reloc: default
     (int 0)))) ; code model: default

(defn link-into!
  "Links the source module into the destination module, destroying the
  source. Returns the destination."
  ^MemorySegment [^MemorySegment dest ^MemorySegment src]
  (let [error (int (LLVM/LLVMLinkModules2 dest src))]
    (when-not (zero? error)
      (throw-error {:destination dest} "LLVM failed to link module")))
  dest)

;;; ---------------------------------------------------------------------------
;;; Bitcode parsing
;;;
;;; The memory buffer is copied, so it may be disposed after parsing. The
;;; returned module is owned by the caller.
;;; ---------------------------------------------------------------------------

(defn parse-bitcode
  "Parses a bitcode byte array into a module in the given context."
  ^MemorySegment [^MemorySegment context bytes ^Arena arena]
  (let [native-bytes (.allocate arena (alength bytes))
        _ (.copyFrom native-bytes (MemorySegment/ofArray bytes))
        buffer (LLVM/LLVMCreateMemoryBufferWithMemoryRangeCopy
                native-bytes
                (long (alength bytes))
                (c-string arena "clodin-module"))
        out (.allocate arena ValueLayout/ADDRESS)
        failed (LLVM/LLVMParseBitcodeInContext2 context buffer out)]
    (try
      (when-not (zero? failed)
        (throw-error {} "LLVM failed to parse bitcode"))
      (.get out ValueLayout/ADDRESS 0)
      (finally
        (LLVM/LLVMDisposeMemoryBuffer buffer)))))

;;; ---------------------------------------------------------------------------
;;; Target initialisation (once)
;;; ---------------------------------------------------------------------------

(defonce ^:private initialised? (atom false))

(defn init-targets!
  "Initialises the native target codegen used by the JIT, if not already
  done."
  []
  (when-not @initialised?
    (let [arch (System/getProperty "os.arch")
          target (case arch
                   "aarch64" "AArch64"
                   ("amd64" "x86_64") "X86"
                   (throw (ex-info (str "Unsupported host architecture: " arch)
                                   {:arch arch})))]
      (doseq [suffix ["TargetInfo" "Target" "TargetMC" "AsmPrinter"]]
        (case (str "LLVMInitialize" target suffix)
          "LLVMInitializeAArch64TargetInfo" (LLVM/LLVMInitializeAArch64TargetInfo)
          "LLVMInitializeAArch64Target" (LLVM/LLVMInitializeAArch64Target)
          "LLVMInitializeAArch64TargetMC" (LLVM/LLVMInitializeAArch64TargetMC)
          "LLVMInitializeAArch64AsmPrinter" (LLVM/LLVMInitializeAArch64AsmPrinter)
          "LLVMInitializeX86TargetInfo" (LLVM/LLVMInitializeX86TargetInfo)
          "LLVMInitializeX86Target" (LLVM/LLVMInitializeX86Target)
          "LLVMInitializeX86TargetMC" (LLVM/LLVMInitializeX86TargetMC)
          "LLVMInitializeX86AsmPrinter" (LLVM/LLVMInitializeX86AsmPrinter)))
      (reset! initialised? true)))
  nil)

;;; ---------------------------------------------------------------------------
;;; ORC / LLJIT
;;; ---------------------------------------------------------------------------

(defn- error-message
  "Reads and frees an LLVMErrorRef message, returning it as a string or
  nil when the error handle is null."
  [^MemorySegment error-ref]
  (when-not (null? error-ref)
    (let [message-pointer (LLVM/LLVMGetErrorMessage error-ref)
          message (some-> message-pointer (.reinterpret 4096) (.getString 0))]
      (LLVM/LLVMDisposeErrorMessage message-pointer)
      message)))

(defn- check-error
  "Throws when error-ref is non-null, including the LLVM error message."
  [^MemorySegment error-ref ^String context]
  (when-let [message (error-message error-ref)]
    (throw-error {:context context} (str context ": " message)))
  error-ref)

(defn create-jit
  "Creates an LLJIT instance whose target machine uses the given clodin
  optimization level (:none :O0 :O1 :O2 :O3). :none uses the LLJIT defaults
  exactly as before; the other levels build the JIT from a target machine at
  the matching codegen optimization level. Returns the JIT handle."
  ^MemorySegment [^Arena arena level]
  (if (= level :none)
    (let [out (.allocate arena ValueLayout/ADDRESS)]
      (check-error (LLVM/LLVMOrcCreateLLJIT out (MemorySegment/NULL))
                   "create LLJIT")
      (.get out ValueLayout/ADDRESS 0))
    (let [codegen-level (int (codegen-opt-level level))
          target-machine (host-target-machine arena codegen-level)
          target-machine-builder (LLVM/LLVMOrcJITTargetMachineBuilderCreateFromTargetMachine target-machine)
          builder (LLVM/LLVMOrcCreateLLJITBuilder)
          _ (LLVM/LLVMOrcLLJITBuilderSetJITTargetMachineBuilder builder target-machine-builder)
          out (.allocate arena ValueLayout/ADDRESS)
          _ (check-error (LLVM/LLVMOrcCreateLLJIT out builder)
                         "create LLJIT")]
      ;; LLVMOrcCreateLLJIT takes ownership of the builder
      (.get out ValueLayout/ADDRESS 0))))

(defn jit-main-dylib
  ^MemorySegment [^MemorySegment jit]
  (LLVM/LLVMOrcLLJITGetMainJITDylib jit))

(defn add-module-to-jit!
  "Adds a bitcode module to the JIT, taking ownership of it."
  [^MemorySegment jit ^MemorySegment module ^MemorySegment context]
  (let [thread-safe-context (LLVM/LLVMOrcCreateNewThreadSafeContextFromLLVMContext context)
        thread-safe-module (LLVM/LLVMOrcCreateNewThreadSafeModule module thread-safe-context)]
    (LLVM/LLVMOrcDisposeThreadSafeContext thread-safe-context)
    (try
      (check-error (LLVM/LLVMOrcLLJITAddLLVMIRModule jit (jit-main-dylib jit) thread-safe-module)
                   "add module to LLJIT")
      (catch Throwable _error
        ;; On failure the JIT did not take ownership, so dispose it ourselves.
        (LLVM/LLVMOrcDisposeThreadSafeModule thread-safe-module)
        (throw _error)))))

(defn lookup-address
  "Returns the machine code address of a symbol in the JIT, or nil if the
  symbol is not present."
  [^MemorySegment jit ^MemorySegment name ^Arena arena]
  (let [out (.allocate arena ValueLayout/JAVA_LONG)
        error (LLVM/LLVMOrcLLJITLookup jit out name)]
    (if (null? error)
      (.get out ValueLayout/JAVA_LONG 0)
      (do
        (error-message error)  ;; consume the message
        nil))))

(defn dispose-jit
  [^MemorySegment jit]
  (check-error (LLVM/LLVMOrcDisposeLLJIT jit) "dispose LLJIT"))