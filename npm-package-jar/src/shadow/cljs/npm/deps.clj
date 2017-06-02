(ns shadow.cljs.npm.deps
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cemerick.pomegranate.aether :as aether]
            ))

(defn transfer-listener
  [{:keys [type error resource] :as info}]
  (let [{:keys [name repository]} resource]
    (binding [*out* *err*]
      (case type
        :started (println "Retrieving" name "from" repository)
        ;; :progressed
        ;; :succeeded
        :corrupted (when error (println (.getMessage error)))
        nil))))

(defn -main [version config-path]
  (let [config-file
        (io/file config-path)

        {:keys [cache-dir repositories dependencies]
         :or {dependencies []
              repositories {}
              cache-dir "target/shadow-cljs"}
         :as config}
        (or (when (.exists config-file)
              (-> (slurp config-file)
                  (edn/read-string)))
            {})

        coords
        (into [['thheller/shadow-cljs version]] dependencies)]

    (println "shadow-cljs - updating dependencies")

    ;; FIXME: resolve conflicts?
    ;; the uberjar contains the dependencies listed in project.clj
    ;; those should be excluded from everything
    (let [deps
          (aether/resolve-dependencies
            :coordinates
            coords
            :repositories
            (merge aether/maven-central {"clojars" "http://clojars.org/repo"} repositories)
            :transfer-listener
            transfer-listener)

          files
          (into [] (map #(.getAbsolutePath %)) (aether/dependency-files deps))

          result
          {:dependencies dependencies
           :version version
           :files files}

          classpath-file
          (io/file cache-dir "classpath.edn")]

      (io/make-parents classpath-file)

      (spit classpath-file (pr-str result)))

    (println "shadow-cljs - dependencies updated")
    ))