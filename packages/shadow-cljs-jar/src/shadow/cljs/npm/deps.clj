(ns shadow.cljs.npm.deps
  (:gen-class)
  (:require
    [clojure.java.io :as io]
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
    (let [{:keys [cache-root repositories proxy local-repo dependencies mirrors version]
           :or {dependencies []
                repositories {}
                cache-root "target/shadow-cljs"}
           :as config}
          (read *in*)]

      (println "shadow-cljs - updating dependencies")

      ;; FIXME: resolve conflicts?
      (let [resolve-args
            (-> {:coordinates
                 dependencies
                 :repositories
                 (merge aether/maven-central {"clojars" "https://clojars.org/repo"} repositories)
                 :transfer-listener
                 transfer-listener}
                (cond->
                  mirrors
                  (assoc :mirrors mirrors)
                  proxy
                  (assoc :proxy proxy)
                  local-repo
                  (assoc :local-repo local-repo)
                  ))

            deps
            (apply aether/resolve-dependencies (into [] (mapcat identity) resolve-args))

            files
            (into [] (map #(.getAbsolutePath %)) (aether/dependency-files deps))

            result
            {:dependencies dependencies
             :version version
             :files files
             :deps deps}

            classpath-file
            (io/file cache-root "classpath.edn")]

        (io/make-parents classpath-file)

        (spit classpath-file (pr-str result)))

      (println "shadow-cljs - dependencies updated"))

    (catch Exception e
      (println "shadow-cljs - dependency update failed -" (.getMessage e))
      (System/exit 1)
      )))