(ns clodin.compiler-test
  "Tests for the procedures clodin.compiler compiles and runs natively.

  Every procedure here is a defproc, and every test calls one through the
  wrapper that defproc generates, so these tests exercise the whole path from
  source form through LLVM to native code. The tests that stay in
  clodin.compiler cover the parts that need no native code: type layout,
  argument checking, name resolution, error messages and reading the stored
  bitcode.

  defproc registers a procedure when the namespace loads, so the procedures
  below are registered for the whole session. Tests that need to define a
  procedure at run time call compiler/define-proc!.

  The global storage tests (the handle, JVM access, the reset and type-change
  rules) and the standalone-build tests stayed in clodin.compiler, because
  they test the storage and the build the compiler owns rather than the
  language's procedures."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :refer [deftest is run-tests testing use-fixtures]]
            [clodin.compiler :as compiler :refer [defproc]]
            [clodin.globals :as globals]
            [clodin.print :as print])
  (:import (java.lang.foreign Arena FunctionDescriptor Linker Linker$Option
                              MemoryLayout MemorySegment SymbolLookup ValueLayout)
           (java.lang.invoke MethodHandles MethodType)))

(use-fixtures :each compiler/isolated-registrations)


;;; ---------------------------------------------------------------------------
;;; Arithmetic, memory, loops and intrinsics
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
    (is (= "clodin.compiler-test"
           (:namespace (get @compiler/procedure-registry
                            "clodin.compiler-test/add-both"))))))

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
      (is (identical? buffer (compiler/coerce-argument :ptr buffer)))
      (is (instance? MemorySegment (compiler/coerce-argument :ptr (.address buffer))))
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

(defproc if-choose [a :i64 b :i64]
  (if (< a b) a b))

(defproc if-store [pointer :ptr flag :boolean]
  (if flag
    (store :i64 pointer 1)
    (store :i64 pointer 2)))

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
    (compiler/define-proc! "clodin.compiler" "branch-test-leaf" [] [] [5] nil false)
    (compiler/define-proc! "clodin.compiler" "branch-test-caller" ['flag] [:boolean]
      '[(if flag (branch-test-leaf) 1)] :i64 false)
    (is (contains? (:callees (get @compiler/procedure-registry
                                  "clodin.compiler/branch-test-caller"))
                   "clodin.compiler/branch-test-leaf")))

  (testing "a global referenced only inside a branch is still recorded"
    (compiler/define-proc! "clodin.compiler" "branch-test-global-reader" ['flag] [:boolean]
      '[(if flag clodin.compiler/globals-test-counter 0)] :i64 false)
    (is (contains? (:globals (get @compiler/procedure-registry
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
         (compiler/define-proc! "clodin.compiler" "if-arm-mismatch" ['pointer] [:ptr]
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
                 (compiler/define-proc! "clodin.compiler" "when-as-value" [] []
                   '[(let [value :i64 (when true 1)] value)] :i64 false)))))

(deftest test-to-f32
  (is (= 2.5 (halve-f32 5.0)))
  (is (= 1.5 (f32-from-f64 1.5)))
  (is (= 3.0 (f32-from-i64 3))))

;;; ---------------------------------------------------------------------------
;;; External C functions
;;; ---------------------------------------------------------------------------

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

;;; ---------------------------------------------------------------------------
;;; Printing, conversion and boolean procedures
;;; ---------------------------------------------------------------------------

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
      (compiler/build-library! 'examples.standalone-lib {:output output})
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
    (is (contains? @compiler/procedure-registry "clodin.core/not")))

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
                          (compiler/define-proc! "clodin.compiler" "not-on-integer" [] []
                            '[(not 1)] :boolean false)))))

(deftest test-not-equal
  (testing "not= is composed from not and ="
    (is (true? (is-different 1 2)))
    (is (false? (is-different 2 2))))

  (testing "not= is no longer a compiler form"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown clodin procedure: not="
                          (compiler/define-proc! "clodin.compiler" "not-equal-removed" [] []
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
  (testing "compiler/set-optimization-level! preserves results at every level"
    (try
      (compiler/set-optimization-level! :O3)
      (is (= 2.5 (halve 5.0)))
      (is (= 7 (add-both 3 4)))
      (finally
        (compiler/set-optimization-level! :none))))
  (testing "rejects unknown levels"
    (is (thrown? clojure.lang.ExceptionInfo
                 (compiler/set-optimization-level! :O9)))))

;;; ---------------------------------------------------------------------------
;;; Reloadable mode
;;; ---------------------------------------------------------------------------

(deftest test-reloadable-redefinition-propagates
  (try
    (compiler/set-build-mode! :reloadable)
    (compiler/define-proc! "clodin.compiler" "reload-test-value" [] [] [1] nil false)
    (compiler/define-proc! "clodin.compiler" "reload-test-caller" [] []
      '[(reload-test-value)] nil false)
    (is (= 1 (compiler/invoke-procedure "reload-test-caller" [])))
    (compiler/define-proc! "clodin.compiler" "reload-test-value" [] [] [2] nil false)
    (testing "an already compiled native caller sees the new body"
      (is (= 2 (compiler/invoke-procedure "reload-test-caller" []))))
    (testing "the body symbol is versioned but the stub keeps its name"
      (is (string/includes? (:body-symbol (get @compiler/procedure-registry
                                               "clodin.compiler/reload-test-value"))
                            "$"))
      (is (= "clodin.compiler.reload-test-value"
             (:stub-symbol (get @compiler/procedure-registry
                                "clodin.compiler/reload-test-value")))))
    (finally
      (compiler/set-build-mode! :monolithic))))

(deftest test-reloadable-preserves-buffer-state
  (let [arena (Arena/ofShared)
        buffer (.allocate arena 8)]
    (try
      (compiler/set-build-mode! :reloadable)
      (compiler/define-proc! "clodin.compiler" "reload-test-bump" ['pointer] [:ptr]
        '[(let [value :i64 (load :i64 pointer)]
            (store :i64 pointer (+ value 1))
            value)]
        :i64 false)
      (is (= 0 (compiler/invoke-procedure "reload-test-bump" [buffer])))
      (is (= 1 (compiler/invoke-procedure "reload-test-bump" [buffer])))
      (compiler/define-proc! "clodin.compiler" "reload-test-bump" ['pointer] [:ptr]
        '[(let [value :i64 (load :i64 pointer)]
            (store :i64 pointer (+ value 10))
            value)]
        :i64 false)
      (testing "the new body reads the memory the old body wrote"
        (is (= 2 (compiler/invoke-procedure "reload-test-bump" [buffer])))
        (is (= 12 (compiler/invoke-procedure "reload-test-bump" [buffer]))))
      (finally
        (compiler/set-build-mode! :monolithic)
        (.close arena)))))

(deftest test-reloadable-signature-change-cascades
  (try
    (compiler/set-build-mode! :reloadable)
    (compiler/define-proc! "clodin.compiler" "reload-test-producer" [] [] [1] nil false)
    (compiler/define-proc! "clodin.compiler" "reload-test-consumer" [] []
      '[(reload-test-producer)] nil false)
    (is (= 1 (compiler/invoke-procedure "reload-test-consumer" [])))
    (compiler/define-proc! "clodin.compiler" "reload-test-producer" [] [] [1.5] nil false)
    (testing "the callers are re-emitted and their inferred types follow"
      (is (= 1.5 (compiler/invoke-procedure "reload-test-producer" [])))
      (is (= 1.5 (compiler/invoke-procedure "reload-test-consumer" [])))
      (is (= :f64 (:return-type (get @compiler/procedure-registry
                                     "clodin.compiler/reload-test-consumer")))))
    (testing "the old stub is replaced by a signature stub"
      (is (not= "clodin.compiler.reload-test-producer"
                (:stub-symbol (get @compiler/procedure-registry
                                   "clodin.compiler/reload-test-producer")))))
    (finally
      (compiler/set-build-mode! :monolithic))))

(deftest test-build-mode-switch
  (try
    (compiler/set-build-mode! :reloadable)
    (compiler/define-proc! "clodin.compiler" "reload-test-mode" [] [] [7] nil false)
    (is (= 7 (compiler/invoke-procedure "reload-test-mode" [])))
    (compiler/set-build-mode! :monolithic)
    (is (= :monolithic (compiler/current-build-mode)))
    (is (= 7 (compiler/invoke-procedure "reload-test-mode" [])))
    (compiler/set-build-mode! :reloadable)
    (is (= :reloadable (compiler/current-build-mode)))
    (is (= 7 (compiler/invoke-procedure "reload-test-mode" [])))
    (finally
      (compiler/set-build-mode! :monolithic))))

(deftest test-reloadable-native-loop-sees-new-body
  (let [arena (Arena/ofShared)
        flag (.allocate arena 8)
        out (.allocate arena 8)
        previous-level (compiler/current-optimization-level)]
    (try
      (compiler/set-optimization-level! :none)
      (compiler/set-build-mode! :reloadable)
      (.set flag ValueLayout/JAVA_LONG 0 0)
      (compiler/define-proc! "clodin.compiler" "reload-test-loop-synth" [] [] [1] nil false)
      (compiler/define-proc! "clodin.compiler" "reload-test-loop-driver" ['flag 'out] [:ptr :ptr]
        '[(let [index :i64 0]
            (while (= (load :i64 flag) 0)
              (buffer-set out :i64 0 (reload-test-loop-synth))
              (set index (+ index 1)))
            index)]
        :i64 false)
      (let [worker (future (compiler/invoke-procedure "reload-test-loop-driver" [flag out]))]
        (try
          (Thread/sleep 200)
          (is (= 1 (.get out ValueLayout/JAVA_LONG 0)))
          (compiler/define-proc! "clodin.compiler" "reload-test-loop-synth" [] [] [2] nil false)
          (Thread/sleep 200)
          (testing "the running native loop picks up the new body"
            (is (= 2 (.get out ValueLayout/JAVA_LONG 0))))
          (finally
            (.set flag ValueLayout/JAVA_LONG 0 1)))
        (is (integer? (deref worker 10000 ::timeout))))
      (finally
        (.set flag ValueLayout/JAVA_LONG 0 1)
        (compiler/set-build-mode! :monolithic)
        (compiler/set-optimization-level! previous-level)
        (.close arena)))))

;;; ---------------------------------------------------------------------------
;;; Globals reached from native code
;;; ---------------------------------------------------------------------------

(defproc globals-test-background-printer [] :i64
  (let [turns :i64 0]
    (while clodin.compiler/globals-test-boolean
      (set turns (+ turns 1)))
    (println "background turns = " turns)
    turns))

(deftest test-globals-native-access
  (try
    (reset! compiler/globals-test-counter 7)
    (reset! compiler/globals-test-boolean true)
    (compiler/define-proc! "clodin.compiler" "globals-test-read-counter" [] []
      '[(+ clodin.compiler/globals-test-counter 1)] nil false)
    (compiler/define-proc! "clodin.compiler" "globals-test-write-counter" ['value] [:i64]
      '[(set clodin.compiler/globals-test-counter value)] nil false)
    (compiler/define-proc! "clodin.compiler" "globals-test-read-boolean" [] []
      '[clodin.compiler/globals-test-boolean] nil false)
    (compiler/define-proc! "clodin.compiler" "globals-test-clear-boolean" [] []
      '[(set clodin.compiler/globals-test-boolean false)] nil false)

    (testing "native code reads a global written by Clojure"
      (is (= 8 (compiler/invoke-procedure "globals-test-read-counter" [])))
      (reset! compiler/globals-test-counter 40)
      (is (= 41 (compiler/invoke-procedure "globals-test-read-counter" []))))

    (testing "a native set is visible to Clojure"
      (compiler/invoke-procedure "globals-test-write-counter" [99])
      (is (= 99 @compiler/globals-test-counter)))

    (testing "a :boolean global crosses the boundary as a boolean"
      (is (true? (compiler/invoke-procedure "globals-test-read-boolean" [])))
      (compiler/invoke-procedure "globals-test-clear-boolean" [])
      (is (false? @compiler/globals-test-boolean))
      (is (false? (compiler/invoke-procedure "globals-test-read-boolean" []))))
    (finally
      (reset! compiler/globals-test-counter 7)
      (reset! compiler/globals-test-boolean true))))

(deftest test-background-print-goes-to-conveyed-out
  (testing "a loop started in a future prints to the future's *out*"
    (reset! compiler/globals-test-boolean true)
    (let [output (java.io.StringWriter.)
          worker (binding [*out* output]
                   (future (globals-test-background-printer)))]
      (try
        (Thread/sleep 100)
        (is (not (realized? worker)))
        (reset! compiler/globals-test-boolean false)
        (is (integer? (deref worker 20000 ::timeout)))
        (is (re-find #"background turns = [0-9]+" (str output)))
        (finally
          (reset! compiler/globals-test-boolean false))))))

(deftest test-globals-native-loop-sees-reset
  (let [previous-level (compiler/current-optimization-level)]
    (try
      ;; Optimization is the point of this test: a plain, non-atomic load of a
      ;; loop invariant is hoisted out of the loop at every level, so an
      ;; optimized build only terminates because the load is atomic.
      (compiler/set-optimization-level! :O3)
      (reset! compiler/globals-test-boolean true)
      (compiler/define-proc! "clodin.compiler" "globals-test-spin" [] []
        '[(let [turns :i64 0]
            (while clodin.compiler/globals-test-boolean
              (set turns (+ turns 1)))
            turns)]
        :i64 false)
      (let [worker (future (compiler/invoke-procedure "globals-test-spin" []))]
        (try
          (Thread/sleep 100)
          (is (not (realized? worker)))
          (reset! compiler/globals-test-boolean false)
          (is (integer? (deref worker 20000 ::timeout)))
          (finally
            (reset! compiler/globals-test-boolean true))))
      (finally
        (reset! compiler/globals-test-boolean true)
        (compiler/set-optimization-level! previous-level)))))

;;; ---------------------------------------------------------------------------
;;; Global storage through a native rebuild
;;; ---------------------------------------------------------------------------

(deftest test-globals-reloadable-preserves-value
  (try
    (compiler/set-build-mode! :reloadable)
    (reset! compiler/globals-test-counter 0)
    (compiler/define-proc! "clodin.compiler" "globals-test-bump" [] []
      '[(let [next-value :i64 (+ clodin.compiler/globals-test-counter 1)]
          (set clodin.compiler/globals-test-counter next-value)
          next-value)]
      :i64 false)
    (is (= 1 (compiler/invoke-procedure "globals-test-bump" [])))
    (is (= 2 (compiler/invoke-procedure "globals-test-bump" [])))
    (testing "a procedure redefinition keeps the live global value"
      (compiler/define-proc! "clodin.compiler" "globals-test-bump" [] []
        '[(let [next-value :i64 (+ clodin.compiler/globals-test-counter 10)]
            (set clodin.compiler/globals-test-counter next-value)
            next-value)]
        :i64 false)
      (is (= 12 (compiler/invoke-procedure "globals-test-bump" [])))
      (is (= 12 @compiler/globals-test-counter)))
    (finally
      (reset! compiler/globals-test-counter 7)
      (compiler/set-build-mode! :monolithic))))

(deftest test-globals-reset-reinitialises
  (try
    (compiler/define-proc! "clodin.compiler" "globals-test-reading" [] []
      '[clodin.compiler/globals-test-counter] nil false)
    ;; A monolithic build replaces the whole JIT and links fresh storage, so a
    ;; global does not survive a rebuild in that mode. This is the documented
    ;; trade-off: stateful programs use :reloadable mode.
    (is (= 7 (compiler/invoke-procedure "globals-test-reading" [])))
    (reset! compiler/globals-test-counter 10)
    (reset! compiler/globals-test-boolean false)
    (is (= 10 (compiler/invoke-procedure "globals-test-reading" [])))
    (compiler/reset-module!)
    (testing "a full reset restores the source initial values"
      (is (= 7 @compiler/globals-test-counter))
      (is (true? @compiler/globals-test-boolean)))
    (testing "re-emitted native code reads the rebuilt storage"
      (is (= 7 (compiler/invoke-procedure "globals-test-reading" []))))
    (finally
      (reset! compiler/globals-test-counter 7))))

(deftest test-globals-forget-unlinked-storage
  ;; A monolithic rebuild links storage only for the globals its procedures
  ;; use. A global left out of the new generation must lose its address: the
  ;; address belongs to the JIT that is about to be disposed, and reading
  ;; through it would dereference freed memory.
  (try
    (compiler/set-build-mode! :monolithic)
    (compiler/define-var! "clodin.compiler" "globals-test-unlinked" :i64 3)
    (compiler/define-proc! "clodin.compiler" "globals-test-unlinked-reader" [] []
      '[globals-test-unlinked] nil false)
    (is (= 3 (compiler/invoke-procedure "globals-test-unlinked-reader" [])))
    (testing "a rebuild that links no user of the global forgets its address"
      (swap! compiler/procedure-registry dissoc
             "clodin.compiler/globals-test-unlinked-reader")
      (swap! compiler/procedure-handles dissoc
             "clodin.compiler/globals-test-unlinked-reader")
      (reset! compiler/module-dirty true)
      (compiler/compile-module!)
      (is (nil? (:address (globals/entry
                           "clodin.compiler/globals-test-unlinked")))))
    (testing "the next access installs fresh storage at the source value"
      (is (= 3 (globals/read-value
                "clodin.compiler/globals-test-unlinked"))))
    (finally
      (swap! compiler/procedure-registry dissoc
             "clodin.compiler/globals-test-unlinked-reader")
      (swap! compiler/procedure-handles dissoc
             "clodin.compiler/globals-test-unlinked-reader")
      (swap! globals/registry dissoc
             "clodin.compiler/globals-test-unlinked"))))


(deftest test-globals-type-change
  (try
    (compiler/set-build-mode! :reloadable)
    (compiler/define-var! "clodin.compiler" "globals-test-retyped" :i64 5)
    (compiler/define-proc! "clodin.compiler" "globals-test-retyped-reader" [] []
      '[globals-test-retyped] nil false)
    (is (= 5 (compiler/invoke-procedure "globals-test-retyped-reader" [])))
    (testing "a type change mints a new native symbol"
      (let [[entry changed?] (globals/register! "clodin.compiler"
                                                "globals-test-retyped" :f64 1.5)]
        (is (true? changed?))
        (is (= "clodin.compiler.globals-test-retyped$g1" (:symbol entry)))
        (is (= :f64 (:type entry)))))
    (testing "the re-emitted body reads the new layout"
      (compiler/define-proc! "clodin.compiler" "globals-test-retyped-reader" [] []
        '[(+ globals-test-retyped 0.5)] nil false)
      (is (= 2.0 (compiler/invoke-procedure "globals-test-retyped-reader" []))))
    (finally
      ;; The registries are keyed by qualified name, so the cleanup has to use
      ;; the qualified names too; otherwise the next run of this test starts
      ;; from the leftovers of the previous one.
      (swap! globals/registry dissoc "clodin.compiler/globals-test-retyped")
      (swap! compiler/procedure-registry dissoc
             "clodin.compiler/globals-test-retyped-reader")
      (swap! compiler/procedure-handles dissoc
             "clodin.compiler/globals-test-retyped-reader")
      (compiler/set-build-mode! :monolithic))))

;;; ---------------------------------------------------------------------------
;;; Helpers that hand native code a real address
;;; ---------------------------------------------------------------------------

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


;;; ---------------------------------------------------------------------------
;;; is-null and pointer-call
;;; ---------------------------------------------------------------------------

(defproc is-null-pointer [pointer :ptr]
  (if (is-null pointer) 1 0))

(defproc pointer-call-strlen [strlen :ptr]
  (pointer-call strlen [:ptr] :i64 "hello"))

(defproc pointer-call-inferred [strlen :ptr]
  (pointer-call strlen [:ptr] :i64 "hello"))

(defproc pointer-call-if-null [callback :ptr value :i64]
  (if (is-null callback)
    -1
    (pointer-call callback [:i64] :i64 value)))

(defproc pointer-call-void-callback [callback :ptr value :i64]
  (pointer-call callback [:i64] :void value))

(deftest test-is-null
  (testing "is-null is a procedure in clodin.core, not a compiler form"
    (is (contains? @compiler/procedure-registry "clodin.core/is-null")))
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
                          (compiler/define-proc! "clodin.compiler" "is-null-non-pointer" [] []
                            '[(if (is-null 5) 1 0)] :i64 false))))

  (testing "is-null expects exactly one operand"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"expects 1 arguments"
                          (compiler/define-proc! "clodin.compiler" "is-null-arity" [] []
                            '[(if (is-null) 1 0)] :i64 false)))))

(deftest test-pointer-call
  (testing "a pointer-call reaches a libc function address"
    (is (= 5 (pointer-call-strlen (libc-symbol "strlen")))))

  (testing "an argument and return type written at the call site are used"
    (is (= :i64 (:return-type (get @compiler/procedure-registry
                                   "clodin.compiler-test/pointer-call-inferred")))))

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
                          (compiler/define-proc! "clodin.compiler" "pointer-call-non-pointer" [] []
                            '[(pointer-call 5 [] :void)] nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes 1 arguments, got 0"
                          (compiler/define-proc! "clodin.compiler" "pointer-call-arity" ['callback] [:ptr]
                            '[(pointer-call callback [:i64] :void)] nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not match pointer-call parameters"
                          (compiler/define-proc! "clodin.compiler" "pointer-call-argument-types"
                            ['callback] [:ptr]
                            '[(pointer-call callback [:i64] :void 1.5)]
                            nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal vector of parameter types"
                          (compiler/define-proc! "clodin.compiler" "pointer-call-non-literal"
                            ['callback] [:ptr]
                            '[(pointer-call callback :i64 :void)]
                            nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"parameter types are"
                          (compiler/define-proc! "clodin.compiler" "pointer-call-void-parameter"
                            ['callback] [:ptr]
                            '[(pointer-call callback [:void] :void)]
                            nil false)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"return type is"
                          (compiler/define-proc! "clodin.compiler" "pointer-call-silly-return"
                            ['callback] [:ptr]
                            '[(pointer-call callback [] :i8)]
                            nil false))))

  (testing "a void call has no value to bind"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"its value is no value"
                          (compiler/define-proc! "clodin.compiler" "pointer-call-void-value"
                            ['callback] [:ptr]
                            '[(let [value :i64 (pointer-call callback [] :void)]
                                value)]
                            nil false)))))

;;; ---------------------------------------------------------------------------
;;; Statement position and :void return types
;;; ---------------------------------------------------------------------------

(defproc store-and-return [pointer :ptr value :i64]
  (store :i64 pointer value)
  value)

(defproc store-only [pointer :ptr value :i64] :void
  (store :i64 pointer value))

(defproc statement-if-mixed-arms [pointer :ptr flag :boolean] :void
  (if flag
    (store-only pointer 1)
    (store-and-return pointer 2)))

(defproc statement-if-nonfinal [pointer :ptr flag :boolean]
  (if flag
    (store-only pointer 3)
    (store-and-return pointer 4))
  (load :i64 pointer))

(defproc statement-let [pointer :ptr flag :boolean]
  (let [ignored :i64 0]
    (if flag
      (store-only pointer 5)
      (store-and-return pointer 6)))
  (load :i64 pointer))

(defproc statement-when [pointer :ptr flag :boolean]
  (when flag
    (if flag
      (store-only pointer 7)
      (store-and-return pointer 8)))
  (load :i64 pointer))

(defproc statement-while [pointer :ptr count :i64]
  (let [index :i64 0]
    (while (< index count)
      (set index (+ index 1))
      (if (= index 0)
        (store-only pointer 9)
        (store-and-return pointer 10)))
    (load :i64 pointer)))

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
         (compiler/define-proc! "clodin.compiler" "statement-if-value-position" ['pointer] [:ptr]
           '[(if true 1 (store :i64 pointer 0))] :i64 false)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"arms must both produce a value of the same type"
         (compiler/define-proc! "clodin.compiler" "statement-if-let-value" ['pointer] [:ptr]
           '[(let [value :i64 (if true 1 (store :i64 pointer 0))] value)]
           nil false)))))

(deftest test-void-return-type
  (testing ":void is a declarable return type and the call produces no value"
    (is (nil? (void-value-body)))
    (is (nil? (store-only (.allocate (Arena/ofShared) 8) 1))))

  (testing "the registry keeps the declaration but reports a void ABI"
    (let [entry (get @compiler/procedure-registry "clodin.compiler-test/void-value-body")]
      (is (nil? (:return-type entry)))
      (is (= :void (:explicit-return-type entry)))))

  (testing "a declared :void body is a statement, so it has no value to return"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no value to return"
                          (compiler/define-proc! "clodin.compiler" "void-declared-i64" ['pointer] [:ptr]
                            '[(if true (store :i64 pointer 1)
                                  (store :i64 pointer 2))]
                            :i64 false))))

  (testing ":void is rejected as a parameter type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot take a :void parameter"
                          (compiler/define-proc! "clodin.compiler" "void-parameter" ['value] [:void]
                            '[1] nil false))))

  (testing "an unsupported declared return type is a clear error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported return type"
                          (compiler/define-proc! "clodin.compiler" "unsupported-return" [] []
                            '[1] :i8 false)))))

;;; ---------------------------------------------------------------------------
;;; Print runtime
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
    (let [callees (:callees (get @compiler/procedure-registry
                                 "clodin.compiler-test/runtime-print-caller"))]
      (is (contains? callees "clodin.runtime/print-bytes"))
      (is (contains? callees "clodin.runtime/print-i64"))
      (is (contains? callees "clodin.runtime/print-newline"))))

  (testing "the compiler loads the runtime before a user body is compiled"
    (is (contains? @compiler/procedure-registry "clodin.runtime/write-to-out"))
    (is (some? (globals/entry writer-global))))

  (testing "a reset installs it again after it was cleared"
    (globals/write-value! writer-global 0)
    (is (nil? (writer-address)))
    (compiler/reset-module!)
    (is (= (print/writer-address) (writer-address))))

  (testing "a monolithic rebuild installs it again after it was cleared"
    (try
      (compiler/set-build-mode! :monolithic)
      (compiler/reset-module!)
      (globals/write-value! writer-global 0)
      (is (nil? (writer-address)))
      (reset! compiler/module-dirty true)
      (compiler/compile-module!)
      (is (= (print/writer-address) (writer-address)))
      (finally
        (compiler/set-build-mode! :monolithic)))))
