(ns shadow.cljs.npm.cli
  (:refer-clojure :exclude (run!))
  (:require
    ["path" :as path]
    ["fs" :as fs]
    ["os" :as os]
    ["child_process" :as cp]
    ["readline-sync" :as rl-sync] ;; FIXME: drop this?
    ["net" :as node-net]
    ["which" :as which]
    [cljs.core.async :as async :refer (go go-loop alt!)]
    [cljs.tools.reader.reader-types :as rt]
    [cljs.tools.reader.edn :as edn]
    [cljs.reader :as reader]
    [clojure.string :as str]
    [goog.object :as gobj]
    [goog.string.format]
    [goog.string :refer (format)]
    [shadow.cljs.config-env :as config-env]
    [shadow.cljs.npm.util :as util]
    [shadow.cljs.npm.client :as client]
    [shadow.cljs.devtools.cli-opts :as opts]
    ))

(defn log [& args]
  (js/process.stderr.write (str (->> args (map str) (str/join " ")) "\n")))

(def jar-version
  (-> (js/require "../../package.json")
      (gobj/get "jar-version")))

(defn file-older-than [a b]
  (let [xa (fs/statSync a)
        xb (fs/statSync b)]
    (> (.-mtime xa) (.-mtime xb))))

(defn ensure-dir [dir]
  (when-not (fs/existsSync dir)
    (let [parent (path/resolve dir "..")]
      (ensure-dir parent))
    ;; node v10 supports #js {:recursive true} option
    ;; don't want to bump the dependency just for that though
    (fs/mkdirSync dir)))

(defn is-directory? [path]
  (-> (fs/lstatSync path)
      (.isDirectory)))

(defn is-windows? []
  (str/includes? js/process.platform "win32"))

(defn run [project-root cmd args proc-opts]
  (let [spawn-opts
        (-> {:cwd project-root
             :stdio "inherit"}
            (merge proc-opts)
            (clj->js))

        executable
        (which/sync cmd #js {:nothrow true})]

    (if-not executable
      (throw (ex-info (str "Executable '" cmd "' not found on system path.") {:cmd cmd :args args}))
      (cp/spawnSync executable (into-array args) spawn-opts))))

;; same as run! but preserves the exit code of the process
;; must be run as the last step since it will kill the node process after
(defn run! [project-root cmd args proc-opts]
  (let [executable (which/sync cmd #js {:nothrow true})]
    (if-not executable
      (do (println (str "Executable '" cmd "' not found on system path."))
          (js/process.exit 1))

      (let [spawn-opts
            (-> {:cwd project-root
                 :env (-> #js {"SHADOW_CLI_PID" js/process.pid}
                          (js/Object.assign js/process.env))
                 :stdio "inherit"}
                (merge proc-opts)
                (clj->js))

            ^js proc
            (cp/spawn executable (into-array args) spawn-opts)]

        (.on proc "error"
          (fn [^js error]
            (if (and error (= "ENOENT" (. error -errno)))
              (log (str "shadow-cljs - failed to execute \"" cmd "\", command not found."))
              (log (str "shadow-cljs - failed to execute \"" cmd "\", " (. error -message))))))

        (.on proc "exit"
          (fn [code signal]
            (js/process.exit code)))

        proc
        ))))

(defn run-java [project-root args opts]
  (let [^js result
        (run project-root "java" args opts)

        status
        (.-status result)]

    (cond
      (zero? status)
      true

      (pos? status)
      (throw (ex-info "java process exit with non-zero exit code" {:tag :java-exit :status status :result result}))

      (and (.-error result) (= "ENOENT" (.. result -error -errno)))
      (do (log "shadow-cljs - java not found, please install a Java8 SDK. (OpenJDK or Oracle)")
          (js/process.exit 1)
          ))))

(def default-config-str
  (util/slurp (path/resolve js/__dirname ".." "default-config.edn")))

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
        false
        ))))

(defn run-init [opts]
  (let [config (path/resolve "shadow-cljs.edn")]
    (log "shadow-cljs - init")
    (log (str "- " config))

    (when (rl-sync/keyInYN "Create?")
      ;; FIXME: ask for default source path, don't just use one
      (fs/writeFileSync config default-config-str)
      (log "shadow-cljs - created default configuration")
      config
      )))

(defn modified-dependencies? [cp-file config]
  (let [cp (-> (util/slurp cp-file)
               (reader/read-string))]

    (or (not= (:version cp) (:version config))
        (not= (:dependencies cp) (:dependencies config))
        )))

;; these might cause trouble when using different versions
;; than expected by shadow-cljs.
(def unwanted-deps
  '#{org.clojure/clojurescript ;; we will always be on the latest version
     org.clojure/clojure ;; can't run on 1.8
     thheller/shadow-cljs ;; just in case, added later

     ;; brought in by shadow-cljs
     ;; breaks cache when ending up with older version
     com.cognitect/transit-clj
     com.cognitect/transit-java
     org.clojure/core.async
     })

(defn drop-unwanted-deps [dependencies]
  (->> dependencies
       (remove (fn [[dep-id & _]]
                 (let [fq-dep-id
                       (if (namespace dep-id)
                         dep-id
                         (symbol (name dep-id) (name dep-id)))]

                   (when (or (contains? unwanted-deps dep-id)
                             (contains? unwanted-deps fq-dep-id))
                     (js/console.warn
                       (str "WARNING: The " dep-id " dependency in shadow-cljs.edn was ignored. Default version is used and override is not allowed to ensure compatibility.\n"
                            "The versions provided by shadow-cljs can be found here: https://clojars.org/thheller/shadow-cljs/versions/" jar-version))
                     true))))
       (into [])))

(defn add-exclusions [dependencies]
  (->> dependencies
       (map (fn [[dep-id version & modifiers :as dep]]
              (let [mods
                    (-> (apply hash-map modifiers)
                        (update :exclusions (fn [excl]
                                              (->> excl
                                                   (concat unwanted-deps)
                                                   (distinct)
                                                   (into [])))))]
                (reduce-kv conj [dep-id version] mods))))
       (into [])))

(defn get-classpath [project-root {:keys [cache-root user-config] :as config}]
  (let [cp-file
        (path/resolve project-root cache-root "classpath.edn")

        use-aot
        (not (false? (get config :aot true)))

        shadow-artifact
        (-> ['thheller/shadow-cljs (or (:version config)
                                       jar-version)]
            (cond->
              use-aot
              (conj :classifier "aot")))


        classpath-config
        (-> config
            ;; allow the system config to add extra deps like cider-nrepl
            (update :dependencies into (:dependencies user-config))
            (update :dependencies drop-unwanted-deps)
            (update :dependencies add-exclusions)
            (update :dependencies #(into [shadow-artifact] %)))

        ;; only need to rebuild the classpath if :dependencies
        ;; or the version changed
        updated?
        (when (or (not (fs/existsSync cp-file))
                  (modified-dependencies? cp-file classpath-config))
          ;; re-create classpath by running the java helper
          (let [jar (js/require "shadow-cljs-jar/path")]
            (run-java
              project-root
              ["-jar" jar]
              {:input (pr-str classpath-config)
               :stdio [nil js/process.stdout js/process.stderr]})
            true))

        {:keys [files] :as classpath-data}
        (-> (util/slurp cp-file)
            (reader/read-string)
            (assoc :updated? updated?))]

    ;; if something in the ~/.m2 directory is deleted we need to re-fetch it
    ;; otherwise we end up with weird errors at runtime
    (if (every? #(fs/existsSync %) files)
      classpath-data
      ;; if anything is missing delete the classpath.edn and start over
      (do (fs/unlinkSync cp-file)
          (log "WARN: missing dependencies, reconstructing classpath.")
          (recur project-root config)
          ))))

(defn print-error [ex]
  (let [{:keys [tag] :as data}
        (ex-data ex)]

    (js/console.warn "===== ERROR =================")
    (js/console.warn (.-message ex))
    (js/console.warn "=============================")
    ))

(defn get-shared-home []
  (path/resolve (os/homedir) ".shadow-cljs" jar-version))

(defn get-jvm-opts [project-root {:keys [source-paths jvm-opts] :as config}]
  (let [classpath
        (get-classpath project-root config)

        classpath-str
        (->> (:files classpath)
             (concat source-paths)
             (str/join path/delimiter))]

    (-> []
        (into jvm-opts)
        (conj "-cp" classpath-str)
        )))

(defn run-standalone [project-root config args opts]
  (let [cli-args
        (-> (get-jvm-opts project-root config)
            (conj "clojure.main" "-m" "shadow.cljs.devtools.cli" "--npm")
            (into args))]

    #_(log "shadow-cljs - starting ...")
    (run! project-root "java" cli-args {})))

(defn get-lein-args [{:keys [lein user-config] :as config} opts]
  (let [{:keys [profile] :as lein-config}
        (cond
          (map? lein)
          lein
          (true? lein)
          {})

        extra-deps
        (-> []
            (into (:dependencies user-config))
            (into (get-in opts [:options :dependencies])))]

    (-> []
        (cond->
          profile
          (conj "with-profile" profile)

          (seq extra-deps)
          (util/reduce->
            (fn [args dep]
              (conj args "update-in" ":dependencies" "conj" (pr-str dep) "--"))
            extra-deps)))))

(defn run-lein [project-root config args opts]
  (let [lein-args
        (-> (get-lein-args config opts)
            (conj "run" "-m" "shadow.cljs.devtools.cli" "--npm")
            (into args))]

    (log "shadow-cljs - running: lein" (str/join " " lein-args))

    (when (seq (:dependencies config))
      (log "==============================================================================")
      (log "WARNING: The configured :dependencies in shadow-cljs.edn were ignored!")
      (log "         When using :lein they must be configured in project.clj!")
      (log "=============================================================================="))

    (when (seq (:source-paths config))
      (log "==============================================================================")
      (log "WARNING: The configured :source-paths in shadow-cljs.edn were ignored!")
      (log "         When using :lein they must be configured in project.clj!")
      (log "=============================================================================="))

    (run! project-root "lein" lein-args {})))

(defn get-clojure-args [project-root {:keys [jvm-opts user-config] :as config} opts]
  (let [{:keys [aliases inject]} (:deps config)

        inject?
        (true? inject)

        ;; unparsed string arg
        opt-aliases
        (get-in opts [:options :aliases])

        extra-deps-vec
        (-> []
            (into (:dependencies user-config))
            (into (get-in opts [:options :dependencies])))

        extra-deps
        (-> {}
            (util/reduce->
              (fn [m [id version]]
                ;; FIXME: don't forget about extra kv args
                (assoc m id {:mvn/version version}))
              extra-deps-vec)
            (cond->
              inject?
              (assoc 'thheller/shadow-cljs {:mvn/version jar-version})))

        aliases
        (-> (or aliases [])
            (into (:deps-aliases user-config))
            (cond->
              (seq extra-deps)
              (conj :shadow-cljs-inject)))]

    (-> []
        (cond->
          (seq extra-deps)
          (conj
            "-Sdeps"
            (pr-str {:aliases {:shadow-cljs-inject {:extra-deps extra-deps}}}))

          (seq aliases)
          (conj (str "-A" (->> aliases (map pr-str) (str/join ""))))

          (seq opt-aliases)
          (conj (str "-A" opt-aliases))

          (seq jvm-opts)
          (into (map #(str "-J" %)) jvm-opts)
          ))))

(defn powershell-escape [s]
  (-> s
      (str/replace " " "` ")
      (str/replace "{" "`{")
      (str/replace "}" "`}")
      (str/replace \" "`\"`\"")))

(defn run-clojure [project-root config args opts]
  (let [clojure-args
        (-> (get-clojure-args project-root config opts)
            (conj "-m" "shadow.cljs.devtools.cli" "--npm")
            (into args))]

    (log "shadow-cljs - starting via \"clojure\"")

    (when (seq (:dependencies config))
      (log "=============================================================================")
      (log "WARNING: The configured :dependencies in shadow-cljs.edn were ignored!")
      (log "         When using :deps they must be configured in deps.edn")
      (log "=============================================================================="))

    (when (seq (:source-paths config))
      (log "==============================================================================")
      (log "WARNING: The configured :source-paths in shadow-cljs.edn were ignored!")
      (log "         When using :deps they must be configured in deps.edn")
      (log "=============================================================================="))

    (if-not (is-windows?)
      (run! project-root "clojure" clojure-args {})
      (let [ps-args (into ["-command" "clojure"] (map powershell-escape) clojure-args)]
        (run! project-root "powershell" ps-args {})))))

(defn wait-for-server-start! [port-file ^js proc]
  (if (fs/existsSync port-file)
    (do (js/process.stderr.write " ready!\n")
        ;; give the server some time to settle before we release it
        ;; hopefully fixes some circleci issues
        (js/setTimeout #(.unref proc) 500))
    (do (js/process.stderr.write ".")
        (js/setTimeout #(wait-for-server-start! port-file proc) 250))
    ))

(defn server-start [project-root {:keys [lein deps cache-root] :as config} args opts]
  (let [[server-cmd server-args]
        (cond
          deps
          ["clojure"
           (-> (get-clojure-args project-root config opts)
               (conj "-m"))]

          lein
          ["lein"
           (-> (get-lein-args config opts)
               (conj "run" "-m"))]

          :else
          ["java"
           (-> (get-jvm-opts project-root config)
               (conj "clojure.main" "-m"))])

        server-args
        (conj server-args "shadow.cljs.devtools.cli" "--npm" "server")]

    (js/process.stderr.write "shadow-cljs - server starting ")

    (let [cache-dir
          (path/resolve project-root cache-root)

          out-path
          (path/resolve cache-dir "server.stdout.log")

          err-path
          (path/resolve cache-dir "server.stderr.log")

          out
          (fs/openSync out-path "a")

          err
          (fs/openSync err-path "a")

          proc
          (cp/spawn server-cmd (into-array server-args)
            #js {:detached true
                 :stdio #js ["ignore", out, err]})]

      (wait-for-server-start! (path/resolve cache-dir "cli-repl.port") proc)
      )))

(defn server-stop [project-root config server-port-file server-pid-file args opts]
  (let [signal (async/chan)

        cli-repl
        (-> (util/slurp server-port-file)
            (js/parseInt 10))

        socket
        (node-net/connect
          #js {:port cli-repl
               :host "localhost"
               :timeout 1000})]

    (.on socket "connect" #(.write socket "(shadow.cljs.devtools.server/remote-stop!)\n:repl/quit\n"))
    (.on socket "error" (fn [err]
                          (fs/unlinkSync server-port-file)
                          (fs/unlinkSync server-pid-file)))
    (.on socket "close" #(async/close! signal))

    signal))

(def defaults
  {:cache-root ".shadow-cljs"
   :version jar-version
   :dependencies []})

(defn merge-config-with-cli-opts [config {:keys [options] :as opts}]
  (let [{:keys [dependencies]} options]
    (-> config
        (cond->
          (seq dependencies)
          (update :dependencies into dependencies)
          ))))

(defn print-classpath-tree
  ([deps]
   (print-classpath-tree deps 0))
  ([deps level]
   (doseq [[coord coord-deps] deps]
     (println
       (str
         (when (pos? level)
           (->> (repeat level "")
                (str/join "  ")))
         (pr-str coord)))
     (when coord-deps
       (print-classpath-tree coord-deps (inc level))))))

(defn print-cli-info [project-root config-path {:keys [cache-root source-paths] :as config} opts]
  (println "=== Version")
  (println "jar:           " jar-version)
  (println "cli:           " (-> (js/require "../../package.json")
                                 (gobj/get "version")))
  (println "deps:          " (-> (js/require "shadow-cljs-jar/package.json")
                                 (gobj/get "version")))
  (println "config-version:" (:version config))
  (println)

  (println "=== Paths")
  (println "cli:    " js/__filename)
  (println "config: " config-path)
  (println "project:" project-root)
  (println "cache:  " cache-root)
  (println)

  (println "=== Java")
  (run-java project-root ["-version"] {})
  (println)

  (println "=== Source Paths")
  (doseq [source-path source-paths]
    (println (path/resolve project-root source-path)))
  (println)

  (when (and (not (:lein config))
             (not (:deps config)))
    (println "=== Dependencies")
    (let [{:keys [deps-hierarchy] :as cp-data}
          (get-classpath project-root config)]

      (print-classpath-tree deps-hierarchy))
    (println)))

(defn read-config* [config-path]
  (try
    (let [reader-opts
          {:readers
           {'shadow/env config-env/read-env
            'env config-env/read-env}}

          config-txt
          (util/slurp config-path)

          rdr
          (rt/source-logging-push-back-reader config-txt)]

      (edn/read reader-opts rdr))

    (catch :default ex
      (throw (ex-info
               (format "Failed to read config file: %s\n%s" config-path (.-message ex))
               {:config-path config-path} ex)))))

(defn read-user-config []
  (let [config-path (path/resolve (os/homedir) ".shadow-cljs" "config.edn")]
    (when (fs/existsSync config-path)
      (read-config* config-path))))

(defn read-config [config-path opts]
  (-> (read-config* config-path)
      (merge-config-with-cli-opts opts)))

(defn guess-node-package-manager [project-root config]
  (or (get-in config [:node-modules :managed-by])
      (let [yarn-lock (path/resolve project-root "yarn.lock")]
        (when (fs/existsSync yarn-lock)
          :yarn))
      :npm))

(defn check-project-install! [project-root config]
  (let [package-json-file
        (path/resolve project-root "package.json")]

    (or (fs/existsSync (path/resolve "node_modules" "shadow-cljs"))
        (and (fs/existsSync package-json-file)
             (let [pkg (js->clj (js/require package-json-file))]
               (or (get-in pkg ["devDependencies" "shadow-cljs"])
                   (get-in pkg ["dependencies" "shadow-cljs"]))))

        ;; not installed
        (do (log "shadow-cljs not installed in project.")
            (log "")

            (if-not (rl-sync/keyInYN "Add it now?")
              false
              (let [[pkg-cmd pkg-args]
                    (case (guess-node-package-manager project-root config)
                      :yarn
                      ["yarn" ["add" "--dev" "shadow-cljs"]]
                      :npm
                      ["npm" ["install" "--save-dev" "shadow-cljs"]])]

                (log (str "Running: " pkg-cmd " " (str/join " " pkg-args)))

                ;; npm installs into wrong location if no package.json is present
                (when-not (fs/existsSync package-json-file)
                  (fs/writeFileSync package-json-file "{}"))

                (cp/spawnSync pkg-cmd (into-array pkg-args) #js {:cwd project-root
                                                                 :stdio "inherit"})
                true))))))

;; FIXME: couldn't find a "nice" xml library to d this for me which wasn't total overkill
;; only nice to have would be prettier output but since its for cursive to look at
;; I don't really care.
(defn generate-xml [struct]
  (cond
    (string? struct)
    struct

    (vector? struct)
    (let [[tag attrs & more] struct
          tag (name tag)]
      (str "<" tag
           (when (map? attrs)
             (->> attrs
                  (map (fn [[key value]]
                         (let [ns (namespace key)]
                           (str ns (when ns ":") (name key) "=\"" value "\"")
                           )))
                  (str/join " ")
                  (str " ")))
           ">"
           (->> (if (map? attrs) more (rest struct))
                (map generate-xml)
                (str/join ""))
           "</" tag ">"))))

(defn generate-pom [project-root config-path {:keys [source-paths dependencies] :as config} opts]
  (let [pom-path
        (path/resolve project-root "pom.xml")]

    (when (or (not (fs/existsSync pom-path))
              (rl-sync/keyInYN (str pom-path " already exists. Overwrite?")))

      ;; FIXME: allow setting this in shadow-cljs.edn
      (let [project-name (path/basename project-root)]

        (fs/writeFileSync pom-path
          (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
               "<!-- generated by shadow-cljs pom, do not edit -->\n"
               (generate-xml
                 [:project {:xmlns "http://maven.apache.org/POM/4.0.0"
                            :xmlns/xsi "http://www.w3.org/2001/XMLSchema-instance"
                            :xsi/schemaLocation "http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd"}
                  [:modelVersion "4.0.0"]
                  [:groupId project-name]
                  [:artifactId project-name]
                  [:version "0.0.1"]
                  [:name project-name]

                  ;; FIXME: need config for this at some point, defaults from lein
                  [:repositories
                   [:repository
                    [:id "central"]
                    [:url "https://repo1.maven.org/maven2/"]
                    [:snapshots [:enabled "false"]]
                    [:releases [:enabled "true"]]]
                   [:repository
                    [:id "clojars"]
                    [:url "https://repo.clojars.org/"]
                    [:snapshots [:enabled "true"]]
                    [:releases [:enabled "true"]]]]

                  (->> (into [['thheller/shadow-cljs jar-version]] dependencies)
                       (map (fn [[dep-sym dep-version & more]]
                              (let [id (name dep-sym)
                                    ns (or (namespace dep-sym) id)]
                                [:dependency
                                 [:groupId ns]
                                 [:artifactId id]
                                 [:version dep-version]])))
                       (into [:dependencies]))

                  (-> [:build]
                      (conj [:sourceDirectory (first source-paths)])
                      (cond->
                        (seq (rest source-paths))
                        (conj [:plugins
                               [:plugin
                                [:groupId "org.codehaus.mojo"]
                                [:artifactId "build-helper-maven-plugin"]
                                [:version "3.1.0"]
                                [:executions
                                 [:execution
                                  [:phase "generate-sources"]
                                  [:goals [:goal "add-source"]]
                                  [:configuration
                                   (->> source-paths
                                        (map (fn [path]
                                               [:source path]))
                                        (into [:sources]))]]]]])))])))))))

;; can't do this because running the server on windows
;; but running shadow-cljs via WSL won't find the PID
;; and think that the server is dead
(defn is-server-running? [server-pid]
  (and (fs/existsSync server-pid)
       (let [pid (-> (util/slurp server-pid)
                     (js/parseInt 10))]
         (try
           ;; returns true if signal succeeded
           (js/process.kill pid 0)
           (catch :default e
             ;; throws ESRCH or other errors if signal failed
             ;; meaning the server isn't reachable
             false
             )))))

(defn do-start [project-root config args opts]
  (cond
    (:deps config)
    (run-clojure project-root config args opts)

    (:lein config)
    (run-lein project-root config args opts)

    :else
    (run-standalone project-root config args opts)))

(defn print-classpath [project-root config opts]
  (cond
    (:deps config)
    (let [clojure-args
          (-> (get-clojure-args project-root config opts)
              (conj "-Spath"))]

      (if-not (is-windows?)
        (run! project-root "clojure" clojure-args {})
        (let [ps-args (into ["-command" "clojure"] (map powershell-escape) clojure-args)]
          (run! project-root "powershell" ps-args {}))))

    (:lein config)
    (let [lein-args
          (-> (get-lein-args config opts)
              (conj "classpath"))]

      (run! project-root "lein" lein-args {}))

    :else
    (let [classpath
          (get-classpath project-root config)

          classpath-str
          (->> (:files classpath)
               (concat (:source-paths config))
               (str/join path/delimiter))]

      (println classpath-str))))

(defn warn-about-missing-project-install! [project-root]
  (try
    (let [pjson-path (path/resolve project-root "package.json")
          pjson (-> (fs/readFileSync pjson-path)
                    (str)
                    (js/JSON.parse)
                    (js->clj))]

      (when-not (or (get-in pjson ["devDependencies" "shadow-cljs"])
                    (get-in pjson ["dependencies" "shadow-cljs"]))

        (println "------------------------------------------------------------------------------")
        (println "   WARNING: shadow-cljs not installed in project.")
        (println "   See https://shadow-cljs.github.io/docs/UsersGuide.html#project-install"))
        (println "------------------------------------------------------------------------------"))

    (catch :default e
      (println "WARNING: package.json not found. See https://shadow-cljs.github.io/docs/UsersGuide.html#project-install"))))

(defn ^:export main [args]

  (try
    (let [{:keys [action options] :as opts}
          (opts/parse args)]

      (cond
        (or (:help options)
            (= action :help))
        (opts/help opts)

        (= action :init)
        (run-init opts)

        :else
        (let [config-path (ensure-config)]
          (if-not config-path
            (do (println "Could not find shadow-cljs.edn config file.")
                (println "To create one run:")
                (println "  shadow-cljs init"))

            (let [project-root
                  (path/dirname config-path)

                  args
                  (into [] args) ;; starts out as JS array

                  user-config
                  (read-user-config)

                  config
                  (read-config config-path opts)

                  {:keys [cache-root version] :as config}
                  (-> (merge defaults config)
                      (cond->
                        user-config
                        (assoc :user-config user-config)))

                  server-port-file
                  (path/resolve project-root cache-root "cli-repl.port")

                  server-pid-file
                  (path/resolve project-root cache-root "server.pid")

                  server-running?
                  (and (fs/existsSync server-port-file)
                       (fs/existsSync server-pid-file))]


              (warn-about-missing-project-install! project-root)

              (ensure-dir (path/resolve project-root cache-root))

              (when (and (not server-running?) (fs/existsSync server-pid-file))
                (log "shadow-cljs - server pid exists but server appears to be dead, proceeding without server.")
                (fs/unlinkSync server-pid-file))

              (log "shadow-cljs - config:" config-path)

              (cond
                (or (:cli-info options)
                    (= :info action))
                (print-cli-info project-root config-path config opts)

                (= :pom action)
                (generate-pom project-root config-path config opts)

                (= :classpath action)
                (print-classpath project-root config opts)

                (= :start action)
                (if server-running?
                  (log "shadow-cljs - server already running")
                  (server-start project-root config args opts))

                (= :stop action)
                (if-not server-running?
                  (log "shadow-cljs - server not running")
                  (server-stop project-root config server-port-file server-pid-file args opts))

                (= :restart action)
                (go (when server-running?
                      (<! (server-stop project-root config server-port-file server-pid-file args opts)))
                    (server-start project-root config args opts))

                (and server-running? (not (:force-spawn options)))
                (client/run project-root config server-port-file opts args
                  #(do-start project-root config args opts))

                :else
                (do-start project-root config args opts)
                ))))))
    (catch :default ex
      (print-error ex)
      (js/process.exit 1))))
