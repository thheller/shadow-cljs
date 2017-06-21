(ns shadow.cljs.npm.cli
  (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)])
  (:require ["path" :as path]
            ["fs" :as fs]
            ["child_process" :as cp]
            ["readline-sync" :as rl-sync] ;; FIXME: drop this?
            ["mkdirp" :as mkdirp]
            [cljs.reader :as reader]
            [cljs.core.async :as async]
            [clojure.string :as str]
            [shadow.cljs.npm.util :as util]
            [shadow.cljs.npm.client :as client]
            [shadow.cljs.devtools.cli-opts :as opts]
            ))

(goog-define jar-version "SNAPSHOT")

(defn file-older-than [a b]
  (let [xa (fs/statSync a)
        xb (fs/statSync b)]
    (> (.-mtime xa) (.-mtime xb))))

(defn ensure-dir [dir]
  (when-not (fs/existsSync dir)
    (fs/mkdirSync dir)))

(defn run [project-root java-cmd java-args proc-opts]
  (let [spawn-opts
        (-> {:cwd project-root
             :stdio "inherit"}
            (merge proc-opts)
            (clj->js))]

    (cp/spawnSync java-cmd (into-array java-args) spawn-opts)))

(defn run-java [project-root args opts]
  (let [result (run project-root "java" args opts)

        status
        (.-status result)]

    (cond
      (zero? status)
      true

      (pos? status)
      (throw (ex-info "java process exit with non-zero exit code" {:tag :java-exit :status status :result result}))

      (and (.-error result) (= "ENOENT" (.. result -error -errno)))
      (do (js/console.log "shadow-cljs - java not found, trying node-jre")
          (prn result)
          (let [jre
                (js/require "node-jre")

                result
                (run project-root (.driver jre) args opts)]

            (when-not (zero? (.-status result))
              (js/console.log "failed to run java", result)
              (js/process.exit 1)
              ))))))

(defn run-lein [project-root {:keys [lein] :as config} args]
  (let [{:keys [profile] :as lein-config}
        (cond
          (map? lein)
          lein
          (true? lein)
          {})

        lein-args
        (->> (concat
               (when profile
                 ["with-profile" profile])
               ["run" "-m" "shadow.cljs.devtools.cli" "--npm"]
               args)
             (into []))]

    (println "shadow-cljs - running: lein" (str/join " " lein-args))
    (run project-root "lein" lein-args {})))

(def default-config-str
  (util/slurp (path/resolve js/__dirname "default-config.edn")))

(def default-config
  (reader/read-string default-config-str))

(defn ensure-config []
  (loop [root (path/resolve)]
    (let [config (path/resolve root "shadow-cljs.edn")]
      (cond
        (fs/existsSync config)
        config

        ;; check parent directory
        ;; might be in $PROJECT/src/demo it should find $PROJECT/shadow-cljs.edn
        (not= root (path/resolve root ".."))
        (recur (path/resolve root ".."))

        :else ;; ask to create default config in current dir
        (let [config (path/resolve "shadow-cljs.edn")]
          (println "shadow-cljs - missing configuration file")
          (println (str "- " config))

          (when (rl-sync/keyInYN "Create one?")
            ;; FIXME: ask for default source path, don't just use one
            (fs/writeFileSync config default-config-str)
            (println "shadow-cljs - created default configuration")
            config
            ))))))

(defn modified-dependencies? [cp-file config]
  (let [cp (-> (util/slurp cp-file)
               (reader/read-string))]

    (or (not= (:version cp) (:version config))
        (not= (:dependencies cp) (:dependencies config))
        )))

(defn get-classpath [project-root {:keys [cache-root version] :as config}]
  (let [cp-file (path/resolve project-root cache-root "classpath.edn")]

    ;; only need to rebuild the classpath if :dependencies
    ;; or the version changed
    (when (or (not (fs/existsSync cp-file))
              (modified-dependencies? cp-file config))

      ;; re-create classpath by running the java helper
      (let [jar (js/require "shadow-cljs-jar/path")]
        (run-java project-root ["-jar" jar] {:input (pr-str config)
                                             :stdio [nil js/process.stdout js/process.stderr]})))

    ;; only return :files since the rest is just cache info
    (-> (util/slurp cp-file)
        (reader/read-string)
        (:files)
        )))

;; FIXME: windows uses ;
(def cp-seperator ":")

(defn run-standalone
  [project-root {:keys [cache-root source-paths] :as config} args]
  (let [aot-path
        (path/resolve project-root cache-root "aot-classes")

        classpath
        (try
          (get-classpath project-root config)
          (catch :default ex
            nil))]

    (when classpath
      (let [classpath-str
            (->> classpath
                 (concat [aot-path])
                 (concat source-paths)
                 (str/join cp-seperator))

            cli-args
            (into ["-cp" classpath-str
                   ;; FIXME: maybe try direct linking?
                   (str "-Dclojure.compile.path=" aot-path)
                   "clojure.main"
                   ;; FIXME: this should only be done if the classpath changes
                   ;; it only adds about 500ms overhead though which isn't that bad
                   ;; and is faster than launching an extra JVM to only do AOT
                   ;; but comparing the timestamps of every jar and only
                   ;; compiling conditionally should still be fastest
                   ;; using do so it doesn't print shadow.cljs.devtools.cli
                   "-e" "(do (compile 'shadow.cljs.devtools.cli) nil)"
                   "-m" "shadow.cljs.devtools.cli"
                   "--npm"]
              args)]

        (mkdirp/sync aot-path)

        (println "shadow-cljs - starting ...")
        (run-java project-root cli-args {})
        ))))

(def defaults
  {:cache-root "target/shadow-cljs"
   :version jar-version
   :dependencies []})

(defn merge-config-with-cli-opts [config {:keys [options] :as opts}]
  (let [{:keys [dependencies]} options]
    (-> config
        (cond->
          (seq dependencies)
          (update :dependencies into dependencies)
          ))))

(defn main [args]
  (let [{:keys [action builds options summary errors] :as opts}
        (opts/parse args)]

    (cond
      (:help options)
      (opts/help opts)

      :else
      (when-let [config-path (ensure-config)]

        (let [project-root
              (path/dirname config-path)

              args
              (into [] args) ;; starts out as JS array

              config
              (-> (util/slurp config-path)
                  (reader/read-string)
                  (merge-config-with-cli-opts opts))]

          (if (not (map? config))
            (do (println "shadow-cljs - old config format no longer supported")
                (println config-path)
                (println "  previously a vector was used to define builds")
                (println "  now {:builds the-old-vector} is expected"))

            (let [{:keys [cache-root version] :as config}
                  (merge defaults config)

                  server-pid
                  (path/resolve project-root cache-root "remote.pid")]

              (println "shadow-cljs - config:" config-path "version:" version)

              (cond
                (fs/existsSync server-pid)
                (client/run project-root config server-pid args)

                (:lein config)
                (run-lein project-root config args)

                :else
                (run-standalone project-root config args)
                ))))))))
