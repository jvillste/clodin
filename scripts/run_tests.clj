(ns run-tests
  "Run every clojure.test test in the project source tree.

  Usage:

    clojure -M:test                  ; every namespace that defines a deftest
    clojure -M:test clodin.names     ; only the named namespaces

  The namespaces are discovered by scanning the :paths of deps.edn for
  files that contain a deftest form, so a new test namespace needs no
  change here. The exit status is the number of failures and errors,
  capped at 255, so a shell can gate on it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.test :as test]))

(def deps-file "deps.edn")

(defn source-roots
  "The directories listed in :paths of deps.edn that exist here."
  []
  (filter #(.isDirectory (io/file %))
          (:paths (edn/read-string (slurp deps-file)))))

(defn clojure-files
  "Every .clj file under root, recursively."
  [root]
  (filter (fn [file]
            (and (.isFile file)
                 (string/ends-with? (.getName file) ".clj")))
          (file-seq (io/file root))))

(defn namespace-name
  "The symbol named by the ns form of source, or nil when it has none."
  [source]
  (some-> (re-find #"\(ns\s+([^\s()]+)" source) second symbol))

(defn test-namespace-names
  "Every namespace under roots whose file defines at least one deftest."
  [roots]
  (into (sorted-set)
        (keep (fn [file]
                (let [source (slurp file)]
                  (when (re-find #"\(deftest\s" source)
                    (namespace-name source)))))
        (mapcat clojure-files roots)))

(defn require-namespaces
  "Require every namespace, exiting with a clear message when one is missing."
  [namespace-names]
  (doseq [namespace-name namespace-names]
    (try
      (require namespace-name)
      (catch java.io.FileNotFoundException _
        (binding [*out* *err*]
          (println "No such namespace:" namespace-name))
        (System/exit 1)))))

(defn run-tests
  "Require namespace-names, run their tests and exit with the failure count."
  [namespace-names]
  (require-namespaces namespace-names)
  (let [summary (apply test/run-tests namespace-names)]
    (shutdown-agents)
    (System/exit (min 255 (+ (:fail summary) (:error summary))))))

(defn -main
  [& arguments]
  (run-tests (if (seq arguments)
               (map symbol arguments)
               (test-namespace-names (source-roots)))))
