(ns examples.printf
  "Shows how to call C library functions from clodin.

  `extern` registers a C function signature. The JIT resolves the symbol from
  the host process, so libc functions such as printf, sprintf and strlen are
  available without linking a library or writing syscall wrappers.

  Clojure string literals inside a procedure body compile to private constant
  null terminated UTF-8 globals and are passed as :ptr, which is exactly what
  printf and friends expect.

  Run it with:

    clojure -M:dev -m examples.printf

  Expected output:

    hello from clodin
    value = 42
    length of hello = 5
    formatted: value = 42 (10 bytes)"
  (:require [clodin.compiler :refer [defproc extern]])
  (:import (java.lang.foreign Arena)))

;;; A variadic C function: [:ptr] lists the fixed arguments (the format
;;; string) and :varargs allows any number of arguments after them.
(extern printf [:ptr] :i32 :varargs)

;;; sprintf has two fixed arguments (destination and format) and is variadic.
(extern sprintf [:ptr :ptr] :i32 :varargs)

;;; strlen is a plain non-variadic function.
(extern strlen [:ptr] :i64)

(defproc greet []
  (printf "hello from clodin\n"))

(defproc print-value [value :i64]
  ;; Integer literals and :i64 parameters use %ld on macOS and Linux, where
  ;; long is 64 bits. Use (to-i32 value) with %d for a 32 bit int.
  (printf "value = %ld\n" value))

(defproc text-length [text :ptr]
  (strlen text))

(defproc print-length []
  (printf "length of hello = %ld\n" (text-length "hello")))

(defproc format-value [buffer :ptr value :i64]
  (sprintf buffer "value = %ld" value))

(defproc print-formatted [buffer :ptr written :i32]
  ;; buffer is a :ptr and written is the :i32 returned by sprintf.
  (printf "formatted: %s (%d bytes)\n" buffer written))

(defn -main [& _]
  (greet)
  (print-value 42)
  (print-length)
  (let [arena (Arena/ofShared)
        buffer (.allocate arena 64)]
    (try
      (print-formatted buffer (format-value buffer 42))
      (finally
        (.close arena)))))

;;; File I/O uses exactly the same pattern:
;;;
;;;   (extern fopen  [:ptr :ptr] :ptr)
;;;   (extern fwrite [:ptr :i64 :i64 :ptr] :i64)
;;;   (extern fclose [:ptr] :i32)
;;;
;;; Pass a path and mode as string literals, or allocate a C string from
;;; Clojure with (.allocateFrom arena path) when the path is dynamic.
