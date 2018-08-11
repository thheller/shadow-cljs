(ns shadow.cljs.npm.cli
  (:refer-clojure :exclude (run!))
  (:require
    ["path" :as path]
    ["fs" :as fs]
    ["os" :as os]
    ["child_process" :as cp]
    ["readline-sync" :as rl-sync] ;; FIXME: drop this?
    ["mkdirp" :as mkdirp]
    ["https" :as https]
    ["net" :as node-net]
    [cljs.core.async :as async :refer (go go-loop alt!)]
    #_[cljs.tools.reader :as reader]
    [cljs.reader :as reader]
    [clojure.string :as str]
    [goog.object :as gobj]
    [goog.string.format]
    [goog.string :refer (format)]
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
    (fs/mkdirSync dir)))

(def cp-separator
  (if (str/starts-with? js/process.platform "win")
    ";"
    ":"))

(defn is-directory? [path]
  (-> (fs/lstatSync path)
      (.isDirectory)))

(defn is-windows? []
  (str/includes? js/process.platform "win32"))

(defn run [project-root cmd args proc-opts]
  (let [spawn-opts
        (-> {:cwd project-root
             :stdio "inherit"}
            (cond->
              ;; can't figure out why lein won't work without this in windows cmd
              ;; probably similar issue with tools.deps but that doesn't exist for windows yet
              ;; need to verify first
              (and (= "lein" cmd) (is-windows?))
              (assoc :shell true
                     :windowsVerbatimArguments true))
            (merge proc-opts)
            (clj->js))]

    (cp/spawnSync cmd (into-array args) spawn-opts)))

;; same as run! but preserves the exit code of the process
;; must be run as the last step since it will kill the node process after
(defn run! [project-root cmd args proc-opts]
  (let [^js result (run project-root cmd args proc-opts)

        error
        (.-error result)

        status
        (.-status result)]

    (cond
      (and error (= "ENOENT" (. error -errno)))
      (log (str "shadow-cljs - failed to execute \"" cmd "\", command not found."))

      error
      (log (str "shadow-cljs - failed to execute \"" cmd "\", " (. error -message)))

      (number? status)
      (js/process.exit status)

      :else
      (log (str "shadow-cljs - command completed without status " (pr-str result)))
      )))

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

(defn print-error [ex]
  (let [{:keys [tag] :as data}
        (ex-data ex)]

    (when (not= tag :java-exit)
      (log "shadow-cljs - error" (.-message ex)))
    ))

(defn get-shared-home []
  (let [path (path/resolve (os/homedir) ".shadow-cljs")]
    (mkdirp/sync path)
    path
    ))

(defn get-jvm-opts [{:keys [launcher-file config] :as state}]
  (let [{:keys [jvm-opts]}
        config]

    (-> [(str "-Dshadow.cljs.jar-version=" jar-version)]
        (into jvm-opts)
        (conj "-jar" launcher-file)
        )))

(defn run-standalone* [{:keys [project-root args] :as state}]
  (let [cli-args
        (-> (get-jvm-opts state)
            (conj "--npm")
            (into args))]

    (log "shadow-cljs - starting ...")
    (run! project-root "java" cli-args {})))

;; https://github.com/shadow-cljs/launcher/releases/download/1.2.0/shadow-cljs-launcher-1.2.0.jar

(defn download-launcher [{:keys [launcher-version launcher-file] :as state} callback]
  (let [launcher-url
        (str "https://github.com/shadow-cljs/launcher/releases/download/"
             launcher-version
             "/shadow-cljs-launcher-"
             launcher-version
             ".jar")

        tmp-file
        (str launcher-file ".tmp")

        file-out
        (fs/createWriteStream tmp-file)

        res-handler
        (fn res-handler [^js res]
          (let [redir-url (gobj/getValueByKeys res "headers" "location")]
            ;; github redirects to some AWS url
            (cond
              (and (<= 300 (.-statusCode res) 400) (seq redir-url))
              (https/get redir-url res-handler)

              (= 404 (.-statusCode res))
              (log "shadow-cljs - launcher download not found!")

              :else
              (let [dl-size (gobj/getValueByKeys res "headers" "content-length")]

                (.on res "data"
                  (fn [buf]
                    ;; FIXME: show some kind of download progress
                    #_ (log "got some bytes" (.-length buf))))

                (.on res "end"
                  (fn []
                    (fs/renameSync tmp-file launcher-file)
                    (callback state)))

                ;; start the actual download
                (.pipe res file-out)))))]

    (log "shadow-cljs - downloading launcher" launcher-version "from" launcher-url)
    (https/get launcher-url res-handler)))

(defn ensure-launcher [state callback]
  (let [override
        (some->
          (get-in state [:config :launcher-override])
          (path/resolve))]

    (if (and override (fs/existsSync override))
      (do (println "launcher-override:" override)
          (callback (assoc state :launcher-file override)))
      (let [launcher-version
            (or (get-in state [:config :launcher-version])
                (-> (js/require "../../package.json")
                    (gobj/get "launcher-version")))

            launcher-file
            (path/resolve (get-shared-home) (str "shadow-cljs-launcher-" launcher-version ".jar"))

            state
            (assoc state
              :launcher-version launcher-version
              :launcher-file launcher-file)]

        ;; FIXME: validate launcher-file?
        (if (fs/existsSync launcher-file)
          (callback state)
          (download-launcher state callback))))))

(defn run-standalone [state]
  (ensure-launcher state run-standalone*))

(defn get-lein-args [{:keys [lein] :as config} opts]
  (let [{:keys [profile] :as lein-config}
        (cond
          (map? lein)
          lein
          (true? lein)
          {})

        extra-deps
        (get-in opts [:options :dependencies])]

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
    (run! project-root "lein" lein-args {})))

(defn get-clojure-args [project-root {:keys [jvm-opts] :as config} opts]
  (let [{:keys [aliases inject]} (:deps config)

        inject?
        (not (false? inject))

        opt-aliases
        (get-in opts [:options :aliases])

        aliases
        (if-not inject?
          aliases
          (conj aliases :shadow-cljs-inject))

        extra-deps
        (-> {'thheller/shadow-cljs {:mvn/version jar-version}}
            (util/reduce->
              (fn [m [id version]]
                (assoc m id {:mvn/version version}))
              (get-in opts [:options :dependencies])))]

    (-> []
        (cond->
          inject?
          (conj
            "-Sdeps"
            (pr-str {:aliases
                     {:shadow-cljs-inject
                      ;; :extra-paths ["target/shadow-cljs/aot"]
                      (-> {:extra-deps extra-deps}
                          (cond->
                            (seq jvm-opts)
                            (assoc :jvm-opts jvm-opts))
                          )}}))

          (seq aliases)
          (conj (str "-A" (->> aliases (map pr-str) (str/join ""))))

          (seq opt-aliases)
          (conj (str "-A" opt-aliases))
          ))))

(defn run-clojure [project-root config args opts]
  (let [clojure-args
        (-> (get-clojure-args project-root config opts)
            (conj "-m" "shadow.cljs.devtools.cli" "--npm")
            (into args))]

    (log "shadow-cljs - starting via \"clojure\"")
    (run! project-root "clojure" clojure-args {})
    ))

(defn wait-for-server-start! [port-file ^js proc]
  (if (fs/existsSync port-file)
    (do (js/process.stderr.write " ready!\n")
        ;; give the server some time to settle before we release it
        ;; hopefully fixes some circleci issues
        (js/setTimeout #(.unref proc) 500))
    (do (js/process.stderr.write ".")
        (js/setTimeout #(wait-for-server-start! port-file proc) 250))
    ))

(defn server-start [{:keys [project-root config args opts] :as state}]
  (let [{:keys [lein deps cache-root]}
        config

        [server-cmd server-args]
        (cond
          deps
          ["clojure"
           (-> (get-clojure-args project-root config opts)
               (conj "-m" "shadow.cljs.devtools.cli"))]

          lein
          ["lein"
           (-> (get-lein-args config opts)
               (conj "run" "-m" "shadow.cljs.devtools.cli"))]

          :else
          ["java"
           (get-jvm-opts state)])

        server-args
        (conj server-args  "--npm" "server")]

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

(defn prettier-m2-path [path]
  (if-let [idx (str/index-of path ".m2")]
    ;; strip .m2/repository/
    (str "[maven] " (subs path (+ idx 15)))
    path
    ))

(defn print-cli-info [project-root config-path {:keys [cache-root source-paths] :as config} opts]
  (println "=== Version")
  (println "cli:           " (-> (js/require "../../package.json")
                                 (gobj/get "version")))
  (println "jar-version:   " jar-version)
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

  (println "=== Dependencies")
  (let [cp-file (path/resolve project-root cache-root "classpath.edn")]
    (println "cache-file:" cp-file)
    (when (fs/existsSync cp-file)
      (let [{:keys [files] :as cp-data}
            (-> (util/slurp cp-file)
                (reader/read-string))]

        (doseq [file files]
          (println (prettier-m2-path file)))
        )))
  (println))

(defn- getenv [envname]
  (str (aget js/process.env envname)))

(defn read-config [config-path opts]
  (try
    (-> (util/slurp config-path)
        (#(reader/read-string {:readers {'shadow/env getenv}} %))
        (merge-config-with-cli-opts opts))
    (catch :default ex
      ;; FIXME: missing tools.reader location information
      ;; FIXME: show error location with excerpt like other warnings
      (throw (ex-info (format "failed reading config file: %s" config-path) {:config-path config-path} ex)))))

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

(defn do-start [{:keys [project-root config args opts] :as state}]
  (cond
    (:deps config)
    (run-clojure project-root config args opts)

    (:lein config)
    (run-lein project-root config args opts)

    :else
    (run-standalone state)))

(defn ^:export main [args]

  ;; https://github.com/tapjs/signal-exit
  ;; without this the shadow-cljs process leaves orphan java processes
  ;; that do not exit when the node process is killed (by closing the terminal)
  ;; just adding this causes the java processes to exit properly ...
  ;; I do not understand why ... but I can still use spawnSync this way so I'll take it
  (let [onExit (js/require "signal-exit")]
    (onExit (fn [code signal])))

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

                  config
                  (read-config config-path opts)

                  {:keys [cache-root version] :as config}
                  (merge defaults config)

                  server-port-file
                  (path/resolve project-root cache-root "cli-repl.port")

                  server-pid-file
                  (path/resolve project-root cache-root "server.pid")

                  server-running?
                  (and (fs/existsSync server-port-file)
                       (fs/existsSync server-pid-file))

                  state
                  {:project-root project-root
                   :args args
                   :config config
                   :server-port-file server-port-file
                   :server-pid-file server-pid-file
                   :server-running? server-running?}]

              (mkdirp/sync (path/resolve project-root cache-root))

              (when (and (not server-running?) (fs/existsSync server-pid-file))
                (log "shadow-cljs - server pid exists but server appears to be dead, proceeding without server.")
                (fs/unlinkSync server-pid-file))

              (log "shadow-cljs - config:" config-path " cli version:" version " node:" js/process.version)

              (cond
                (or (:cli-info options)
                    (= :info action))
                (print-cli-info project-root config-path config opts)

                (= :pom action)
                (generate-pom project-root config-path config opts)

                (= :start action)
                (if server-running?
                  (log "shadow-cljs - server already running")
                  (server-start state))

                (= :stop action)
                (if-not server-running?
                  (log "shadow-cljs - server not running")
                  (server-stop project-root config server-port-file server-pid-file args opts))

                (= :restart action)
                (go (when server-running?
                      (<! (server-stop project-root config server-port-file server-pid-file args opts)))
                    (server-start state))

                (and server-running? (not (:force-spawn options)))
                (client/run project-root config server-port-file opts args
                  #(do-start state))

                :else
                (do-start state)
                ))))))
    (catch :default ex
      (print-error ex)
      (js/process.exit 1))))
