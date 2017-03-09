(ns shadow.devtools.sass
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [shadow.util FileWatcher FS]
           [java.io File]
           (clojure.lang IFn)
           (java.lang AutoCloseable)))

(def sassc-executable "sassc")

(defn build-module [^File file ^File target-file]
  {:pre [(or (not (.exists target-file))
             (not (.isDirectory target-file)))
         (.exists file)]}
  (io/make-parents target-file)
  ;; can't use sh because we have an uneven number of args which sh doesn't understand
  (let [proc (-> (ProcessBuilder. [sassc-executable "-m" "-t" "compressed" (.getAbsolutePath file) (.getAbsolutePath target-file)])
                 (.directory (.getParentFile file))
                 (.inheritIO)
                 (.start))]
    (.waitFor proc)
    (when-not (zero? (.exitValue proc))
      (throw (ex-info (str "failed to build css module: " file) {})))))

(defn generate-manifest [target-dir module-data]
  (let [file (io/file target-dir "manifest.json")]
    (spit file (json/write-str module-data))
    file))

(comment
  (def css-package
    {:name "test"
     :modules ["test-css/mod-a.scss"
               "test-css/mod-b.scss"]
     :public-dir "target/test-css-out/css"
     :public-path "css"}))

(defn as-file [it]
  (cond
    (string? it)
    (io/file it)

    (instance? java.io.File it)
    it

    :else
    (throw (ex-info "invalid module (expect string or file)" {:module it}))))

(defn build-package [{:keys [public-dir rename-fn modules] :as pkg}]
  (let [rename-fn
        (or rename-fn identity)

        modules
        (mapv as-file modules)

        public-dir
        (as-file public-dir)

        manifest
        (reduce
          (fn [manifest module]
            (let [module-file-name (.getName module)
                  module-name (subs module-file-name 0 (.lastIndexOf module-file-name "."))
                  module-out-name (rename-fn (str/replace module-file-name #"scss$" "css"))
                  target-file (io/file public-dir module-out-name)]
              (build-module module target-file)
              (assoc manifest module-name module-out-name)
              ))
          {}
          modules)]
    (let [manifest-file (generate-manifest public-dir manifest)]
      (assoc pkg
        :modules modules
        :public-dir public-dir
        :manifest-file manifest-file
        :manifest manifest))))

(defn build-packages [pkgs]
  (mapv build-package pkgs))

(defn create-package-watch [{:keys [modules] :as pkg}]
  (let [dirs
        (->> modules
             (map #(.getParentFile %))
             (into #{}))

        watchers
        (->> dirs
             (map #(FileWatcher/create % ["scss"]))
             (into []))

        n
        (count watchers)

        ;; returns true of this package got dirty since last check
        ;; FIXME: should at some point check indiviual modules so we don't recompile everything all the time
        ;; but that would require figuring out which includes belong to which module, I don't want to deal
        ;; with parsing css for now.
        dirty-checker
        (reify
          AutoCloseable
          (close [_]
            (doseq [w watchers]
              (.close w)))
          IFn
          (invoke [_]
            (loop [i 0]
              (if (>= i n)
                false
                (let [^FileWatcher watcher (nth watchers i)
                      test (.pollForChanges watcher)]
                  (if (seq test)
                    true
                    (recur (inc i)))
                  )))))]

    (assoc pkg :dirty-check dirty-checker)
    ))

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
