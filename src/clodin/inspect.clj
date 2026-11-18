(ns clodin.inspect
  "Inspect the code clodin generates for a procedure.

  (llvm-ir \"add-both\")     ; textual LLVM IR from the stored bitcode
  (assembly \"add-both\")    ; target assembly after optimization
  (disassembly \"add-both\") ; objdump/otool output for the emitted object file"
  (:require [clodin.annotate :as annotate]
            [clodin.compiler :as compiler]
            [clodin.llvm-api :as llvm]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]])
  (:import (clodin.llvm LLVM)
           (java.lang.foreign Arena)
           (java.nio.charset StandardCharsets)))

(defn- procedure-bitcode
  [procedure-name]
  (or (:bitcode (get @compiler/procedure-registry (str procedure-name)))
      (throw (ex-info (str "Unknown clodin procedure: " procedure-name)
                      {:procedure procedure-name}))))

(defn- with-procedure-module
  [procedure-name f]
  (let [arena (Arena/ofConfined)]
    (try
      (let [context (llvm/new-context)
            module (llvm/parse-bitcode context (procedure-bitcode procedure-name) arena)]
        (try
          (f module arena)
          (finally
            (llvm/dispose-module module)
            (llvm/dispose-context context))))
      (finally
        (.close arena)))))

(defn llvm-ir
  "Returns the LLVM IR stored for the named procedure."
  [procedure-name]
  (with-procedure-module procedure-name
    (fn [module _arena]
      (llvm/module-to-string module))))

(defn- optimize-if-requested
  [module arena optimization-level]
  (let [level (or optimization-level (compiler/current-optimization-level))]
    (when-not (= :none level)
      (llvm/optimize-module! module arena level))))

(defn assembly
  "Returns the target assembly for the named procedure.

  Options:
    :optimization-level  :none, :O0, :O1, :O2 or :O3 (defaults to the
                         compiler's current level)"
  ([procedure-name] (assembly procedure-name {}))
  ([procedure-name {:keys [optimization-level]}]
   (with-procedure-module procedure-name
     (fn [module arena]
       (llvm/init-targets!)
       (let [target-machine (llvm/create-target-machine arena)]
         (try
           (llvm/set-module-target! module target-machine arena)
           (optimize-if-requested module arena optimization-level)
           (String. (llvm/emit-to-memory-buffer target-machine module
                                                (LLVM/LLVMAssemblyFile) arena)
                    StandardCharsets/UTF_8)
           (finally
             (llvm/dispose-target-machine target-machine))))))))

(defn- try-shell
  [& args]
  (try
    (apply shell/sh args)
    (catch java.io.IOException _ nil)))

(defn- disassemble-object
  [object-file]
  (let [path (.getAbsolutePath object-file)
        objdump (try-shell "objdump" "-d" path)]
    (if (and objdump (zero? (:exit objdump)))
      (:out objdump)
      (let [otool (try-shell "otool" "-tvV" path)]
        (if (and otool (zero? (:exit otool)))
          (:out otool)
          (throw (ex-info "No working disassembler found (tried objdump and otool)"
                          {:objdump objdump :otool otool})))))))

(defn disassembly
  "Returns the disassembled machine code for the named procedure.

  Emits an object file with LLVM, then runs objdump (Linux) or otool (macOS).
  Options are the same as assembly."
  ([procedure-name] (disassembly procedure-name {}))
  ([procedure-name {:keys [optimization-level]}]
   (with-procedure-module procedure-name
     (fn [module arena]
       (llvm/init-targets!)
       (let [target-machine (llvm/create-target-machine arena)
             object-bytes (try
                            (llvm/set-module-target! module target-machine arena)
                            (optimize-if-requested module arena optimization-level)
                            (llvm/emit-to-memory-buffer target-machine module
                                                        (LLVM/LLVMObjectFile) arena)
                            (finally
                              (llvm/dispose-target-machine target-machine)))
             object-file (java.io.File/createTempFile
                          (str "clodin-" procedure-name "-") ".o")]
         (try
           (io/copy object-bytes object-file)
           (disassemble-object object-file)
           (finally
             (io/delete-file object-file true))))))))

(defn module-llvm-ir
  "Returns the LLVM IR of the linked module for namespace and the namespaces
  it calls into. Does not require a procedure named main.

  Options:
    :extra-namespaces  additional namespaces to include"
  ([namespace] (module-llvm-ir namespace {}))
  ([namespace options]
   (compiler/with-linked-module
    (compiler/procedures-for-namespace namespace options)
    (fn [module _arena]
      (llvm/module-to-string module)))))

(defn module-assembly
  "Returns the target assembly of the linked module for namespace and the
  namespaces it calls into. Does not require a procedure named main.

  Options:
    :extra-namespaces    additional namespaces to include
    :optimization-level  :none, :O0, :O1, :O2 or :O3"
  ([namespace] (module-assembly namespace {}))
  ([namespace {:keys [optimization-level] :as options}]
   (compiler/with-linked-module
    (compiler/procedures-for-namespace namespace options)
    (fn [module arena]
      (llvm/init-targets!)
      (let [target-machine (llvm/create-target-machine arena)]
        (try
          (llvm/set-module-target! module target-machine arena)
          (optimize-if-requested module arena optimization-level)
          (String. (llvm/emit-to-memory-buffer target-machine module
                                               (LLVM/LLVMAssemblyFile) arena)
                   StandardCharsets/UTF_8)
          (finally
            (llvm/dispose-target-machine target-machine))))))))

(defn module-disassembly
  "Returns the disassembled machine code of the linked module for namespace
  and the namespaces it calls into. Does not require a procedure named main.

  Options:
    :extra-namespaces    additional namespaces to include
    :optimization-level  :none, :O0, :O1, :O2 or :O3"
  ([namespace] (module-disassembly namespace {}))
  ([namespace {:keys [optimization-level] :as options}]
   (compiler/with-linked-module
    (compiler/procedures-for-namespace namespace options)
    (fn [module arena]
      (llvm/init-targets!)
      (let [target-machine (llvm/create-target-machine arena)
            object-bytes (try
                           (llvm/set-module-target! module target-machine arena)
                           (optimize-if-requested module arena optimization-level)
                           (llvm/emit-to-memory-buffer target-machine module
                                                       (LLVM/LLVMObjectFile) arena)
                           (finally
                             (llvm/dispose-target-machine target-machine)))
            object-file (java.io.File/createTempFile "clodin-module-" ".o")]
        (try
          (io/copy object-bytes object-file)
          (disassemble-object object-file)
          (finally
            (io/delete-file object-file true))))))))

(defn annotate-proc-assembly
  "Returns the named procedure's assembly annotated with descriptions."
  ([procedure-name] (annotate/annotate-assembly (assembly procedure-name)))
  ([procedure-name options]
   (annotate/annotate-assembly (assembly procedure-name options))))

(defn print-annotated-proc-assembly!
  "Prints the named procedure's assembly with descriptions."
  ([procedure-name] (annotate/print-annotated-assembly! (assembly procedure-name)))
  ([procedure-name options]
   (annotate/print-annotated-assembly! (assembly procedure-name options))))

(defn annotate-module-assembly
  "Returns the linked module's assembly annotated with descriptions."
  ([namespace] (annotate/annotate-assembly (module-assembly namespace)))
  ([namespace options]
   (annotate/annotate-assembly (module-assembly namespace options))))

(defn print-annotated-module-assembly!
  "Prints the linked module's assembly with descriptions."
  ([namespace] (annotate/print-annotated-assembly! (module-assembly namespace)))
  ([namespace options]
   (annotate/print-annotated-assembly! (module-assembly namespace options))))

(defn annotate-proc-disassembly
  "Returns the named procedure's disassembly annotated with descriptions."
  ([procedure-name] (annotate/annotate-disassembly (disassembly procedure-name)))
  ([procedure-name options]
   (annotate/annotate-disassembly (disassembly procedure-name options))))

(defn print-annotated-proc-disassembly!
  "Prints the named procedure's disassembly with descriptions."
  ([procedure-name]
   (annotate/print-annotated-disassembly! (disassembly procedure-name)))
  ([procedure-name options]
   (annotate/print-annotated-disassembly! (disassembly procedure-name options))))

(defn annotate-module-disassembly
  "Returns the linked module's disassembly annotated with descriptions."
  ([namespace] (annotate/annotate-disassembly (module-disassembly namespace)))
  ([namespace options]
   (annotate/annotate-disassembly (module-disassembly namespace options))))

(defn print-annotated-module-disassembly!
  "Prints the linked module's disassembly with descriptions."
  ([namespace]
   (annotate/print-annotated-disassembly! (module-disassembly namespace)))
  ([namespace options]
   (annotate/print-annotated-disassembly! (module-disassembly namespace options))))

(defn print-llvm-ir!
  "Prints the LLVM IR stored for the named procedure."
  ([procedure-name] (println (llvm-ir procedure-name)))
  ([procedure-name options] (println (llvm-ir procedure-name options))))

(defn print-assembly!
  "Prints the target assembly for the named procedure."
  ([procedure-name] (println (assembly procedure-name)))
  ([procedure-name options] (println (assembly procedure-name options))))

(defn print-disassembly!
  "Prints the disassembled machine code for the named procedure."
  ([procedure-name] (println (disassembly procedure-name)))
  ([procedure-name options] (println (disassembly procedure-name options))))

(defn print-module-llvm-ir!
  "Prints the LLVM IR of the linked module for a namespace."
  ([namespace] (println (module-llvm-ir namespace)))
  ([namespace options] (println (module-llvm-ir namespace options))))

(defn print-module-assembly!
  "Prints the target assembly of the linked module for a namespace."
  ([namespace] (println (module-assembly namespace)))
  ([namespace options] (println (module-assembly namespace options))))

(defn print-module-disassembly!
  "Prints the disassembled machine code of the linked module for a namespace."
  ([namespace] (println (module-disassembly namespace)))
  ([namespace options] (println (module-disassembly namespace options))))

(deftest test-llvm-ir
  (testing "the stored bitcode prints as llvm ir"
    (is (re-find #"define i64 @add-both" (llvm-ir "add-both")))))

(deftest test-assembly
  (testing "the procedure emits target assembly"
    (is (re-find #"add-both" (assembly "add-both")))))

(deftest test-annotate-assembly
  (testing "instructions get plain-english descriptions"
    (is (re-find #"x22 = x3"
                 (annotate/annotate-assembly "\tmov\tx22, x3"))))
  (testing "vector instructions describe their lanes"
    (is (re-find #"v18 \(4 x f32\) = v3 \(4 x f32\) - v16 \(4 x f32\)"
                 (annotate/annotate-assembly
                  "\tfsub\tv18.4s, v3.4s, v16.4s"))))
  (testing "structure stores describe the element type and post-index"
    (let [annotated (annotate/annotate-assembly
                    "\tst2.4s\t{ v4, v5 }, [x9], #32")]
      (is (re-find #"store 2 interleaved vectors" annotated))
      (is (re-find #"v4 \(4 x f32\)" annotated))
      (is (re-find #"advance the address by 32 bytes" annotated))))
  (testing "vector compares and horizontal adds are described"
    (is (re-find #"lanes of v1 \(4 x f32\) > v6 \(4 x f32\)"
                 (annotate/annotate-assembly "\tfcmgt\tv5.4s, v1.4s, v6.4s")))
    (is (re-find #"horizontal add"
                 (annotate/annotate-assembly "\tfaddp\ts2, v2.2s"))))
  (testing "suffixed simd mnemonics are described"
    (is (re-find #"v0 \(4 x f32\) = v1 \(4 x f32\) \+ v2 \(4 x f32\)"
                 (annotate/annotate-assembly "\tfadd.4s\tv0, v1, v2"))))
  (testing "conditional compares are described"
    (is (re-find #"compare x0 with x8; otherwise set flags to 2"
                 (annotate/annotate-assembly "\tccmp\tx0, x8, #2, lo")))))

(deftest test-annotate-disassembly
  (testing "objdump instruction lines are annotated"
    (is (re-find #"x0 = x1 \+ x0"
                 (annotate/annotate-disassembly
                  "   0:\t8b000020 \tadd\tx0, x1, x0"))))
  (testing "objdump function labels are annotated"
    (is (re-find #"function add-both"
                 (annotate/annotate-disassembly
                  "0000000000000000 <add-both>:"))))
  (testing "otool instruction lines are annotated"
    (is (re-find #"return to caller"
                 (annotate/annotate-disassembly
                  "0000000000000004\tret"))))
  (testing "macos space-separated instruction lines are annotated"
    (is (re-find #"compare x2 with immediate #0x1"
                 (annotate/annotate-disassembly
                  "       0: f100045f         cmp    x2, #0x1")))))

(deftest test-module-inspection-without-main
  (require 'examples.standalone-lib)
  (testing "module inspection works for a namespace without main"
    (is (re-find #"define i32 @greet"
                 (module-llvm-ir 'examples.standalone-lib)))
    (is (re-find #"greet"
                 (module-assembly 'examples.standalone-lib)))))

(deftest test-print-functions
  (testing "print-*! prints the inspection output"
    (is (= (str (llvm-ir "add-both") "\n")
           (with-out-str (print-llvm-ir! "add-both"))))
    (is (= (str (module-llvm-ir 'examples.standalone-lib) "\n")
           (with-out-str
             (print-module-llvm-ir! 'examples.standalone-lib))))))
