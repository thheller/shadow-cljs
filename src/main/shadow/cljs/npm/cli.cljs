(ns shadow.cljs.npm.cli
  (:require ["path" :as path]
            ["fs" :as fs]
            ["child_process" :as cp]
            ["readline-sync" :as rl]
            ["mkdirp" :as mkdirp]
            [cljs.reader :as reader]
            [clojure.string :as str]))

(def version (js/require "./version.json"))

(defn slurp [file]
  (-> (fs/readFileSync file)
      (.toString)))

(defn file-older-than [a b]
  (let [xa (fs/statSync a)
        xb (fs/statSync b)]
    (> (.-mtime xa) (.-mtime xb))))

(defn ensure-dir [dir]
  (when-not (fs/existsSync dir)
    (fs/mkdirSync dir)))

(defn run [java-cmd java-args]
  (cp/spawnSync java-cmd (into-array java-args) #js {:stdio "inherit"}))

(defn run-java [args]
  (let [result (run "java" args)]

    (if (zero? (.-status result))
      true
      (when (and (.-error result) (= "ENOENT" (.. result -error -errno)))

        (js/console.log "shadow-cljs - java not found, trying node-jre")

        (let [jre
              (js/require "node-jre")

              result
              (run (.driver jre) args)]

          (when-not (zero? (.-status result))
            (js/console.log "failed to run java", result)
            (js/process.exit 1)
            ))))))

(def default-config-str
  (str "{:source-paths [\"src\"]\n"
       " :dependencies []\n"
       " :builds\n"
       " {}}\n"))

(def default-config
  {:source-paths []
   :dependencies []
   :version version
   :cache-dir "target/shadow-cljs"
   :builds
   {}})

(defn ensure-config []
  (let [config (path/resolve "shadow-cljs.edn")]
    (if (fs/existsSync config)
      config
      (do (println "shadow-cljs - missing configuration file")
          (println (str "- " config))

          (when (rl/keyInYN "Create one?")
            ;; FIXME: ask for default source path, don't just use one
            (fs/writeFileSync config default-config-str)
            (println "shadow-cljs - created default configuration")
            config
            )))))

(defn modified-dependencies? [cp-file config]
  (let [cp (-> (slurp cp-file)
               (reader/read-string))]

    (or (not= (:version cp) (:version config))
        (not= (:dependencies cp) (:dependencies config))
        )))

(defn get-classpath [config-path {:keys [cache-dir] :as config}]
  (let [cp-file (path/resolve cache-dir "classpath.edn")]

    ;; only need to rebuild the classpath if :dependencies
    ;; or the version changed
    (when (or (not (fs/existsSync cp-file))
              (modified-dependencies? cp-file config))

      ;; re-create classpath by running the java helper
      (let [jar (js/require "shadow-cljs-jar/path")]
        (run-java ["-jar" jar version config-path])))

    ;; only return :files since the rest is just cache info
    (-> (slurp cp-file)
        (reader/read-string)
        (:files)
        )))

;; FIXME: windows uses ;
(def cp-seperator ":")

(defn aot-compile [aot-path classpath]
  (let [version-file (path/resolve aot-path "version")]

    ;; FIXME: is it enough to AOT only when versions change?
    (when (or (not (fs/existsSync version-file))
              (not= version (slurp version-file)))

      (mkdirp/sync aot-path)

      (print "shadow-cljs - optimizing startup")

      (run-java
        ["-cp" classpath
         ;; FIXME: maybe try direct linking?
         (str "-Dclojure.compile.path=" aot-path)
         "clojure.main"
         "-e" "(compile 'shadow.cljs.devtools.cli)"])

      (fs/writeFileSync version-file version)
      )))

(defn main [& args]
  (when-let [config-path (ensure-config)]
    (println "shadow-cljs -" version "using" config-path)

    (let [config
          (-> (slurp config-path)
              (reader/read-string))]

      (if-not (map? config)
        (do (println "shadow-cljs - old config format no longer supported")
            (println "  previously a vector was used to define builds")
            (println "  now {:builds the-old-vector} is expected"))

        ;; config file found
        ;; check if classpath is up to date
        (let [{:keys [cache-dir source-paths dependencies] :as config}
              (merge default-config config)

              aot-path
              (path/resolve cache-dir "aot-classes")

              classpath
              (->> (get-classpath config-path config)
                   (concat [aot-path])
                   (concat source-paths)
                   (str/join cp-seperator))

              cli-args
              (into ["-cp" classpath "shadow.cljs.devtools.cli" "--npm"] args)]

          (aot-compile aot-path classpath)

          (println "shadow-cljs - starting ...")
          (run-java cli-args)
          )))))
