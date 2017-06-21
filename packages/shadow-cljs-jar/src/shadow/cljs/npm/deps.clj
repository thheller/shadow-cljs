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

(defn -main []
  (try
    (let [{:keys [cache-root repositories dependencies version]
           :or {dependencies []
                repositories {}
                cache-root "target/shadow-cljs"}
           :as config}
          (read *in*)

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
            (io/file cache-root "classpath.edn")]

        (io/make-parents classpath-file)

        (spit classpath-file (pr-str result)))

      (println "shadow-cljs - dependencies updated"))

    (catch Exception e
      (println "shadow-cljs - dependency update failed -" (.getMessage e))
      (System/exit 1)
      )))