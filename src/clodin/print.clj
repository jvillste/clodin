(ns clodin.print
  "JVM half of the native print runtime.

  A clodin procedure cannot write to *out* directly: *out* is a JVM Writer,
  while native code only knows libc streams. This namespace therefore provides
  a JVM upcall stub whose address the compiler stores in the clodin.runtime
  global clodin-write after every build. clodin.runtime's write-to-out loads
  that pointer and calls it with the bytes to print; the upcall runs on the
  calling JVM thread, so it sees that thread's *out* binding. with-out-str,
  CIDER and a future that conveys bindings all work without any capture lock.

  An offline build has no JVM, so the pointer stays null and write-to-out falls
  back to write(1, ...), which is what a standalone binary wants.

  A native thread that the JVM has never attached is attached for the upcall,
  but it has no Clojure binding frame, so it sees the root *out* rather than a
  REPL binding. JVM-created threads (future, Thread) are fine.

  Everything above the boundary is ordinary clodin source in clodin.runtime:
  the formatting helpers, snprintf and the fallback. This namespace holds only
  what cannot be written in clodin."

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
