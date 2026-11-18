(ns build
  "Build and install tasks (tools.build).

  Usage: clojure -T:build install"
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b]))

(def lib 'clodin/clodin)
(def version "0.1.0")
(def class-dir "build/classes")
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def jar-file (format "build/%s-%s.jar" (name lib) version))

(defn copy-files
  "Recursively copy every file under each src-dir into target-dir, preserving
  each file's relative path from its src-dir root. Merges several source trees
  (plain source + generated LLVM classes) without clobbering shared
  top-level package directories."
  [src-dirs target-dir]
  (let [target (.toPath (io/file target-dir))]
    (doseq [src-dir src-dirs
            f (filter #(.isFile %) (file-seq (io/file src-dir)))]
      (let [rel  (.relativize (.toPath (io/file src-dir)) (.toPath f))
            dest (.resolve target rel)]
        (io/make-parents (.toFile dest))
        (io/copy f (.toFile dest))))))

(defn clean [_]
  (b/delete {:path "build"}))

(defn install [_]
  (let [basis @basis]
    (b/delete {:path "build"})
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]})
    ;; include the compiled LLVM (jextract) bindings along with the source
    (copy-files ["src" "target/current"] class-dir)
    (b/jar {:class-dir class-dir
            :jar-file jar-file})
    (b/install {:basis basis
                :lib lib
                :version version
                :jar-file jar-file
                :class-dir class-dir})
    (println "Installed to ~/.m2/repository:" (str lib version))))
