(ns shadow.cljs.npm.deps
  (:gen-class)
  (:require
    [clojure.java.io :as io]
    [cemerick.pomegranate.aether :as aether]
    [clojure.repl :refer (pst)]
    [clojure.string :as str]))

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

;; https://github.com/technomancy/leiningen/blob/da695d4104f1df567e4cd3421dc193f906f27ae6/leiningen-core/src/leiningen/core/classpath.clj#L195-L203
;; s3 fails with Caused by: java.lang.IllegalArgumentException: Secret key cannot be null.
;; if :private-key-file is not set
(defn ensure-s3p-private-key-file [repos]
  (reduce-kv
    (fn [repos name repo]
      (if (and (map? repo)
               (:url repo)
               (str/starts-with? (:url repo) "s3p"))
        (assoc repos name (merge {:private-key-file ""} repo))
        repos
        ))
    repos
    repos))

(defn get-deps
  [{:keys [cache-root repositories proxy local-repo dependencies mirrors version]
    :or {dependencies []
         repositories {}
         cache-root ".shadow-cljs"}
    :as state}]

  (println "shadow-cljs - updating dependencies")

  ;; FIXME: resolve conflicts?
  (let [resolve-args
        (-> {:coordinates
             dependencies
             :repositories
             (merge
               aether/maven-central
               {"clojars" "https://clojars.org/repo"}
               (ensure-s3p-private-key-file repositories))
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
        (apply aether/resolve-dependencies (into [] (mapcat identity) resolve-args))]

    (println "shadow-cljs - dependencies updated")

    (assoc state
      :deps-resolved deps
      :deps-hierarchy (aether/dependency-hierarchy dependencies deps))))

(comment
  (get-deps '{:dependencies
              [[thheller/shadow-cljs "2.6.2"]]}))

(defn write-classpath-edn
  [{:keys [cache-root
           dependencies
           version
           deps-hierarchy
           deps-resolved]
    :or {cache-root ".shadow-cljs"}
    :as state}]

  (let [files
        (into [] (map #(.getAbsolutePath %)) (aether/dependency-files deps-resolved))

        result
        {:dependencies dependencies
         :version version
         :files files
         :deps-hierarchy deps-hierarchy
         :deps-resolved deps-resolved}

        classpath-file
        (io/file cache-root "classpath.edn")]

    (io/make-parents classpath-file)

    (spit classpath-file (pr-str result))

    state
    ))

(defn -main []
  (try
    (aether/register-wagon-factory! "s3" #(org.springframework.build.aws.maven.SimpleStorageServiceWagon.))
    (aether/register-wagon-factory! "s3p" #(org.springframework.build.aws.maven.PrivateS3Wagon.))

    (-> (read *in*)
        (get-deps)
        (write-classpath-edn))
    (catch Exception e
      (println "shadow-cljs - dependency update failed -" (.getMessage e))
      (pst e)
      (System/exit 1)
      )))