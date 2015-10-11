(ns shadow.sass
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [shadow.util FileWatcher FS]
           [java.io File]))

(def sassc-executable "sassc")

(defn build-module [^File file ^File target-file]
  {:pre [(not (.isDirectory target-file))]}
  ;; can't use sh because we have an uneven number of args which sh doesn't understand
  (let [proc (-> (ProcessBuilder. [sassc-executable "-m" "-t" "compressed" (.getAbsolutePath file) (.getAbsolutePath target-file)])
                 (.directory (.getParentFile file))
                 (.inheritIO)
                 (.start))]
    (.waitFor proc)
    (when-not (zero? (.exitValue proc))
      (throw (ex-info (str "failed to build css module: " file) {})))))


(defn build-modules [modules target-dir rename-fn]
  (reduce
    (fn [mods mod]
      (let [target-file (io/file target-dir (rename-fn (str/replace (.getName mod) #"scss$" "css")))]
        (build-module mod target-file)
        (assoc mods (let [name (.getName mod)]
                      (.substring name 0 (.lastIndexOf name ".")))
                    (.getName target-file))
        ))
    {}
    modules))

(defn generate-manifest [target-dir module-data]
  (spit (io/file target-dir "manifest.json")
        (json/write-str module-data)))

(defn build-with-manifest
  ([modules target-dir]
   (build-with-manifest modules target-dir identity))
  ([modules target-dir rename-fn]
   (let [module-data (build-modules modules target-dir rename-fn)]
     (generate-manifest target-dir module-data)
     module-data
     )))

(defn build-all
  ([source-dir target-dir]
   (build-all source-dir target-dir identity))
  ([^File source-dir ^File target-dir rename-fn]
   (build-modules (FS/glob source-dir "*.scss") target-dir rename-fn)))

(comment
  (defn build-and-repeat! [^File source-dir target-dir rename-fn]
    (let [watcher (FileWatcher/create source-dir ["scss"])]
      (loop []
        ;; BUILD STUFF
        ;; FIXME: should only compile modules that were affected by changes
        ;; for now we don't actually care what changed, just recompile everything
        (.waitForChanges watcher)
        (recur)
        ))))
