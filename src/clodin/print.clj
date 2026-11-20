(ns clodin.print
  "Native print runtime for clodin.

  A clodin procedure cannot write to *out* directly: *out* is a JVM Writer,
  while native code only knows libc streams. The runtime therefore gives the
  JIT a function pointer to a JVM upcall. clodin$write$bytes loads that pointer and
  calls it with the bytes to print; the upcall runs on the calling JVM thread,
  so it sees that thread's *out* binding. with-out-str, CIDER and a future
  that conveys bindings all work without any capture lock.

  An offline build has no JVM, so the pointer is null and clodin$write$bytes falls
  back to write(1, ...), which is what a standalone binary wants.

  A native thread that the JVM has never attached is attached for the upcall,
  but it has no Clojure binding frame, so it sees the root *out* rather than a
  REPL binding. JVM-created threads (future, Thread) are fine.

  The runtime module also carries the formatting helpers the compiler calls:
  clodin$print$i64, clodin$print$i32, clodin$print$f64, clodin$print$f32,
  clodin$print$bool, clodin$print$cstr, clodin$print$bytes and
  clodin$print$newline. Formatting happens in native code with snprintf, so a
  print costs one upcall for the whole value, not one per byte."
  (:require [clodin.llvm-api :as llvm])
  (:import (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemorySegment ValueLayout)
           (java.lang.invoke MethodHandles MethodType)
           (java.nio.charset StandardCharsets)))

(defonce ^:private upcall-arena (Arena/ofShared))

(defn- write-bytes!
  "Writes native bytes to the current thread's *out*. Runs as a JVM upcall on
  the thread that entered the native procedure."
  [^MemorySegment bytes ^long length]
  (try
    (let [text (String. (.toArray (.reinterpret bytes length) ValueLayout/JAVA_BYTE)
                        StandardCharsets/UTF_8)]
      (.write ^java.io.Writer *out* text)
      (.flush ^java.io.Writer *out*))
    (catch Throwable error
      ;; An exception must not unwind through native frames.
      (try
        (binding [*out* *err*]
          (println "clodin print failed:" (.getMessage error)))
        (catch Throwable _ nil)))))

(defonce ^:private writer-stub
  (delay
    (let [lookup (MethodHandles/publicLookup)
          invoke (-> (.findVirtual lookup clojure.lang.IFn "invoke"
                                   (MethodType/methodType Object
                                                          (into-array Class [Object Object])))
                     (.bindTo (fn [segment length] (write-bytes! segment length))))
          handle (.asType invoke
                          (MethodType/methodType Void/TYPE
                                                 (into-array Class [MemorySegment Long/TYPE])))
          descriptor (FunctionDescriptor/ofVoid
                      (into-array java.lang.foreign.MemoryLayout
                                  [ValueLayout/ADDRESS ValueLayout/JAVA_LONG]))]
      ;; Clojure's reflection cannot match the varargs upcallStub overload, so
      ;; pass the empty option array explicitly.
      (clojure.lang.Reflector/invokeInstanceMethod
       (Linker/nativeLinker) "upcallStub"
       (to-array [handle descriptor upcall-arena (make-array Linker$Option 0)])))))

(defn writer-address
  "The address of the JVM writer upcall. Valid for the lifetime of the
  process."
  ^long []
  (.address ^MemorySegment @writer-stub))

;;; ---------------------------------------------------------------------------
;;; Runtime module
;;; ---------------------------------------------------------------------------

(defn- declare-function
  "Declares an external function, or finds an existing definition."
  [module context arena name return-type param-types varargs?]
  (let [function-type (llvm/function-type context arena return-type param-types varargs?)]
    [function-type
     (llvm/get-or-add-function module (llvm/c-string arena name) function-type)]))

(defn- define-function
  "Adds a new function definition to the module."
  [module context arena name return-type param-types]
  (let [function-type (llvm/function-type context arena return-type param-types)]
    [function-type
     (llvm/add-function module (llvm/c-string arena name) function-type)]))

(defn- build-write-function
  "Builds clodin$write$bytes(ptr data, i64 length). It calls the JVM upcall when
  writer-address is non-nil and falls back to write(1, ...) otherwise."
  [module context arena writer-address]
  (let [[write-type write-fn] (declare-function module context arena "write"
                                                :i64 [:i32 :ptr :i64] false)
        writer (llvm/add-global module (llvm/type-for context :ptr)
                                (llvm/c-string arena "clodin$write"))
        _ (llvm/set-alignment writer 8)
        _ (llvm/set-initializer writer
                                (if writer-address
                                  (llvm/const-int-to-ptr context writer-address)
                                  (llvm/const-null (llvm/type-for context :ptr))))
        [function-type function] (define-function module context arena "clodin$write$bytes"
                                                  nil [:ptr :i64])
        builder (llvm/new-builder context)
        _ (llvm/append-entry-block function builder context arena "entry")
        data (llvm/get-param function 0)
        length (llvm/get-param function 1)
        writer-value (llvm/build-load builder (llvm/type-for context :ptr) writer arena "writer")
        is-null (llvm/build-icmp builder '= writer-value
                                 (llvm/const-null (llvm/type-for context :ptr)) arena)
        upcall-block (llvm/new-block context arena "upcall")
        fallback-block (llvm/new-block context arena "fallback")
        _ (llvm/append-block function upcall-block)
        _ (llvm/append-block function fallback-block)
        _ (llvm/build-cond-br builder is-null fallback-block upcall-block)
        upcall-type (llvm/function-type context arena nil [:ptr :i64])
        _ (llvm/position-at-end builder upcall-block)
        _ (llvm/build-call builder upcall-type writer-value [data length] arena)
        _ (llvm/build-ret-void builder)
        _ (llvm/position-at-end builder fallback-block)
        _ (llvm/build-call builder write-type write-fn
                           [(llvm/const-int-of-type context :i32 1) data length] arena)
        _ (llvm/build-ret-void builder)]
    (llvm/dispose-builder builder)
    function-type))

(defn- build-format-function
  "Builds clodin_print_<name>(value) by formatting value with snprintf into a
  stack buffer and writing the result. format is a C format string."
  [module context arena name value-type format]
  (let [[snprintf-type snprintf-fn] (declare-function module context arena "snprintf"
                                                      :i32 [:ptr :i64 :ptr] true)
        [write-type write-fn] (declare-function module context arena "clodin$write$bytes"
                                                nil [:ptr :i64] false)
        [function-type function] (define-function module context arena name nil [value-type])
        builder (llvm/new-builder context)
        _ (llvm/append-entry-block function builder context arena "entry")
        value (llvm/get-param function 0)
        buffer (llvm/build-alloca builder (llvm/array-type (llvm/i8-type context) 32) arena "buffer")
        format-string (llvm/build-global-string context module arena format
                                                (str ".clodin.print." name))
        length (llvm/build-call builder snprintf-type snprintf-fn
                                [buffer (llvm/const-int-of-type context :i64 32)
                                 format-string value] arena)
        length64 (llvm/build-sext builder length (llvm/type-for context :i64) arena nil)
        _ (llvm/build-call builder write-type write-fn [buffer length64] arena)
        _ (llvm/build-ret-void builder)]
    (llvm/dispose-builder builder)
    function-type))

(defn- build-bool-function
  [module context arena]
  (let [[write-type write-fn] (declare-function module context arena "clodin$write$bytes"
                                                nil [:ptr :i64] false)
        [function-type function] (define-function module context arena "clodin$print$bool"
                                                  nil [:boolean])
        builder (llvm/new-builder context)
        _ (llvm/append-entry-block function builder context arena "entry")
        value (llvm/get-param function 0)
        true-text (llvm/build-global-string context module arena "true" ".clodin.print.true")
        false-text (llvm/build-global-string context module arena "false" ".clodin.print.false")
        text (llvm/build-select builder value true-text false-text arena)
        length (llvm/build-select builder value
                                  (llvm/const-int-of-type context :i64 4)
                                  (llvm/const-int-of-type context :i64 5)
                                  arena)
        _ (llvm/build-call builder write-type write-fn [text length] arena)
        _ (llvm/build-ret-void builder)]
    (llvm/dispose-builder builder)
    function-type))

(defn- build-cstr-function
  [module context arena]
  (let [[strlen-type strlen-fn] (declare-function module context arena "strlen"
                                                  :i64 [:ptr] false)
        [write-type write-fn] (declare-function module context arena "clodin$write$bytes"
                                                nil [:ptr :i64] false)
        [function-type function] (define-function module context arena "clodin$print$cstr"
                                                  nil [:ptr])
        builder (llvm/new-builder context)
        _ (llvm/append-entry-block function builder context arena "entry")
        value (llvm/get-param function 0)
        is-null (llvm/build-icmp builder '= value
                                 (llvm/const-null (llvm/type-for context :ptr)) arena)
        print-block (llvm/new-block context arena "print")
        done-block (llvm/new-block context arena "done")
        _ (llvm/append-block function print-block)
        _ (llvm/append-block function done-block)
        _ (llvm/build-cond-br builder is-null done-block print-block)
        _ (llvm/position-at-end builder print-block)
        length (llvm/build-call builder strlen-type strlen-fn [value] arena)
        _ (llvm/build-call builder write-type write-fn [value length] arena)
        _ (llvm/build-br builder done-block)
        _ (llvm/position-at-end builder done-block)
        _ (llvm/build-ret-void builder)]
    (llvm/dispose-builder builder)
    function-type))

(defn- build-bytes-function
  [module context arena]
  (let [[write-type write-fn] (declare-function module context arena "clodin$write$bytes"
                                                nil [:ptr :i64] false)
        [function-type function] (define-function module context arena "clodin$print$bytes"
                                                  nil [:ptr :i64])
        builder (llvm/new-builder context)
        _ (llvm/append-entry-block function builder context arena "entry")
        _ (llvm/build-call builder write-type write-fn
                           [(llvm/get-param function 0) (llvm/get-param function 1)] arena)
        _ (llvm/build-ret-void builder)]
    (llvm/dispose-builder builder)
    function-type))

(defn- build-newline-function
  [module context arena]
  (let [[write-type write-fn] (declare-function module context arena "clodin$write$bytes"
                                                nil [:ptr :i64] false)
        [function-type function] (define-function module context arena "clodin$print$newline"
                                                  nil [])
        builder (llvm/new-builder context)
        _ (llvm/append-entry-block function builder context arena "entry")
        text (llvm/build-global-string context module arena "\n" ".clodin.print.newline")
        _ (llvm/build-call builder write-type write-fn
                           [text (llvm/const-int-of-type context :i64 1)] arena)
        _ (llvm/build-ret-void builder)]
    (llvm/dispose-builder builder)
    function-type))

(defn- build-f32-function
  [module context arena]
  (let [[f64-type f64-fn] (declare-function module context arena "clodin$print$f64"
                                            nil [:f64] false)
        [function-type function] (define-function module context arena "clodin$print$f32"
                                                  nil [:f32])
        builder (llvm/new-builder context)
        _ (llvm/append-entry-block function builder context arena "entry")
        value (llvm/get-param function 0)
        extended (llvm/build-fpext builder value (llvm/type-for context :f64) arena nil)
        _ (llvm/build-call builder f64-type f64-fn [extended] arena)
        _ (llvm/build-ret-void builder)]
    (llvm/dispose-builder builder)
    function-type))

(defn runtime-module
  "Builds the print runtime as an LLVM module. writer-address is the JVM
  upcall address for a JIT build or nil for an offline build, where output
  falls back to file descriptor 1."
  [context arena writer-address]
  (let [module (llvm/new-module context "clodin.print")]
    (build-write-function module context arena writer-address)
    (build-format-function module context arena "clodin$print$i64" :i64 "%ld")
    (build-format-function module context arena "clodin$print$i32" :i32 "%d")
    (build-format-function module context arena "clodin$print$f64" :f64 "%g")
    (build-f32-function module context arena)
    (build-bool-function module context arena)
    (build-cstr-function module context arena)
    (build-bytes-function module context arena)
    (build-newline-function module context arena)
    module))
