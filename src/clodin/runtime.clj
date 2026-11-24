(ns clodin.runtime
  "Native half of the print runtime, written in clodin itself.

  The compiler lowers (print ...) and (println ...) to the procedures here:
  print-bytes, print-i64, print-i32, print-f64, print-f32, print-bool,
  print-cstr and print-newline. They are ordinary procedures, so they are
  compiled, reloaded and linked like any other, and the compiler reaches them
  by their qualified names in this namespace.

  Only write-to-out crosses the boundary. It calls clodin-write, which the
  compiler points at a JVM upcall stub after every build, so the upcall runs on
  the thread that entered the native procedure and sees that thread's *out*.
  An offline build leaves the pointer null and write-to-out falls back to
  write(1, ...), which is what a standalone binary wants.

  clodin.print holds the JVM half: the upcall stub and the writer."
  (:require [clodin.compiler :refer [defproc defvar extern]]))

(extern snprintf [:ptr :i64 :ptr] :i32 :varargs)
(extern strlen [:ptr] :i64)
(extern write [:i32 :ptr :i64] :i64)

(defvar clodin-write :ptr nil)

(defproc write-to-out [data :ptr length :i64] :void
  (if (is-null clodin-write)
    (write (to-i32 1) data length)
    (pointer-call clodin-write [:ptr :i64] :void data length)))

(defproc print-bytes [data :ptr length :i64] :void
  (write-to-out data length))

(defproc print-newline [] :void
  (print-bytes "\n" 1))

;; snprintf returns the length it would have written, so a truncated format
;; would make the write read past the scratch block. Clamping keeps the read
;; inside the 32 bytes that were reserved.
(defproc print-i64 [value :i64] :void
  (let [scratch :ptr (stack-allocate 32 16)
        length :i32 (snprintf scratch 32 "%ld" value)]
    (print-bytes scratch (min (to-i64 length) 32))))

(defproc print-i32 [value :i32] :void
  (let [scratch :ptr (stack-allocate 32 16)
        length :i32 (snprintf scratch 32 "%d" value)]
    (print-bytes scratch (min (to-i64 length) 32))))

(defproc print-f64 [value :f64] :void
  (let [scratch :ptr (stack-allocate 32 16)
        length :i32 (snprintf scratch 32 "%g" value)]
    (print-bytes scratch (min (to-i64 length) 32))))

(defproc print-f32 [value :f32] :void
  (print-f64 (to-float value)))

;; The lengths are the byte counts of "true" and "false". There is no way to
;; ask a string literal for its length yet, and strlen after a strlen would
;; only repeat the compiler's work.
(defproc print-bool [value :boolean] :void
  (if value
    (print-bytes "true" 4)
    (print-bytes "false" 5)))

(defproc print-cstr [value :ptr] :void
  (when (= (is-null value) false)
    (print-bytes value (strlen value))))
