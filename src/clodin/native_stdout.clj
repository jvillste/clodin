(ns clodin.native-stdout
  "Captures the C stdout stream and forwards it to *out*.

  Native printf writes to the C stdout stream, which nREPL and CIDER do not
  capture: the REPL captures the JVM's *out*, not file descriptor 1.
  capture-native-stdout redirects the C stdout FILE stream to an in-memory
  stream for the duration of a thunk, then writes the captured text to *out*."
  (:import (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemorySegment ValueLayout)
           (java.nio.charset StandardCharsets)))

(def ^:private linker (Linker/nativeLinker))
(def ^:private lookup (.defaultLookup linker))
(defonce ^:private capture-lock (Object.))

(defn- downcall
  [name return-layout argument-layouts]
  (let [handle (.downcallHandle linker
                                (.orElseThrow (.find lookup name))
                                (FunctionDescriptor/of
                                 return-layout
                                 (into-array java.lang.foreign.MemoryLayout argument-layouts))
                                (make-array Linker$Option 0))]
    (fn [& args] (.invokeWithArguments handle (object-array args)))))

(def ^:private open-memstream
  (downcall "open_memstream" ValueLayout/ADDRESS
            [ValueLayout/ADDRESS ValueLayout/ADDRESS]))
(def ^:private close-stream
  (downcall "fclose" ValueLayout/JAVA_INT [ValueLayout/ADDRESS]))
(def ^:private free-pointer
  (downcall "free" ValueLayout/ADDRESS [ValueLayout/ADDRESS]))

(defn- stdout-address
  "The address of the C stdout FILE* variable. Linux exports stdout, macOS
  exports __stdoutp."
  []
  (let [symbol (or (.orElse (.find lookup "stdout") nil)
                   (.orElse (.find lookup "__stdoutp") nil))]
    (when-not symbol
      (throw (ex-info "Cannot find the C stdout symbol" {})))
    (.reinterpret symbol 8)))

(defn- captured-text
  [pointer size]
  (if (pos? size)
    (String. (.toArray (-> (MemorySegment/ofAddress pointer) (.reinterpret size))
                       ValueLayout/JAVA_BYTE)
             StandardCharsets/UTF_8)
    ""))

(defn capture-native-stdout
  "Runs thunk with the C stdout stream redirected to a memory stream, writes
  the captured text to *out*, and returns thunk's value.

  The output is written when thunk returns, not while it runs. Captures are
  serialized so concurrent calls do not interleave their C stdout."
  [thunk]
  (locking capture-lock
    (let [out *out*
          arena (Arena/ofConfined)
          stdout (stdout-address)
          pointer-slot (.allocate arena ValueLayout/JAVA_LONG)
          size-slot (.allocate arena ValueLayout/JAVA_LONG)
          memstream (open-memstream pointer-slot size-slot)
          previous (.get stdout ValueLayout/ADDRESS 0)]
      (when (zero? (.address memstream))
        (.close arena)
        (throw (ex-info "open_memstream failed" {})))
      (try
        (.set stdout ValueLayout/ADDRESS 0 memstream)
        (thunk)
        (finally
          (.set stdout ValueLayout/ADDRESS 0 previous)
          (close-stream memstream)
          (let [pointer (.get pointer-slot ValueLayout/JAVA_LONG 0)
                size (.get size-slot ValueLayout/JAVA_LONG 0)]
            (when (pos? size)
              (.write out (captured-text pointer size))
              (.flush out))
            (free-pointer (MemorySegment/ofAddress pointer)))
          (.close arena))))))

(defmacro with-native-stdout
  "Evaluates body with the C stdout stream captured and written to *out*.

  (with-native-stdout
    (my-clodin-proc 42))

  This is for REPL use: C printf writes to the process stdout, which nREPL and
  CIDER do not capture. The body's value is returned."
  [& body]
  `(capture-native-stdout (fn [] ~@body)))
