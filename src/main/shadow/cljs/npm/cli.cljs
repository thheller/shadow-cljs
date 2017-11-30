(ns shadow.cljs.npm.cli
  (:require
    ["path" :as path]
    ["fs" :as fs]
    ["child_process" :as cp]
    ["readline-sync" :as rl-sync] ;; FIXME: drop this?
    ["mkdirp" :as mkdirp]
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

(def cp-seperator
  (if (str/starts-with? js/process.platform "win")
    ";"
    ":"))

(defn is-directory? [path]
  (-> (fs/lstatSync path)
      (.isDirectory)))

(defn run [project-root java-cmd java-args proc-opts]
  (let [spawn-opts
        (-> {:cwd project-root
             :stdio "inherit"}
            (merge proc-opts)
            (clj->js))]

    (cp/spawnSync java-cmd (into-array java-args) spawn-opts)))

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
      (do (js/console.log "shadow-cljs - java not found, please install a Java8 SDK. (OpenJDK or Oracle)")
          (js/process.exit 1)
          ))))

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
    (println "shadow-cljs - init")
    (println (str "- " config))

    (when (rl-sync/keyInYN "Create?")
      ;; FIXME: ask for default source path, don't just use one
      (fs/writeFileSync config default-config-str)
      (println "shadow-cljs - created default configuration")
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
     })

(defn drop-unwanted-deps [dependencies]
  (->> dependencies
       (remove (fn [[dep-id & _]]
                 (contains? unwanted-deps dep-id)))
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

(defn get-classpath [project-root {:keys [cache-root version] :as config}]
  (let [cp-file
        (path/resolve project-root cache-root "classpath.edn")

        classpath-config
        (-> config
            (update :dependencies drop-unwanted-deps)
            (update :dependencies add-exclusions)
            (update :dependencies #(into [['thheller/shadow-cljs jar-version]] %)))

        ;; only need to rebuild the classpath if :dependencies
        ;; or the version changed
        updated?
        (when (or (not (fs/existsSync cp-file))
                  (modified-dependencies? cp-file classpath-config))
          ;; re-create classpath by running the java helper
          (let [jar (js/require "shadow-cljs-jar/path")]
            (run-java project-root ["-jar" jar] {:input (pr-str classpath-config)
                                                 :stdio [nil js/process.stdout js/process.stderr]})
            true))]

    ;; only return :files since the rest is just cache info
    (-> (util/slurp cp-file)
        (reader/read-string)
        (assoc :updated? updated?))))

(defn remove-class-files [path]
  (when (fs/existsSync path)
    ;; shadow-cljs - error ENOENT: no such file or directory, unlink '...'
    ;; I have no idea how readdir can find a file but then not find it when
    ;; trying to delete it?
    (doseq [file (into [] (fs/readdirSync path))
            :let [file (path/resolve path file)]]
      (cond
        (str/ends-with? file ".class")
        (when (fs/existsSync file)
          (try
            (fs/unlinkSync file)
            (catch :default e
              (prn [:failed-to-delete file]))))

        (is-directory? file)
        (remove-class-files file)

        :else
        nil
        ))))

(defn print-error [ex]
  (let [{:keys [tag] :as data}
        (ex-data ex)]

    (when (not= tag :java-exit)
      (println "shadow-cljs - error" (.-message ex)))
    ))

(defn logging-config [project-root {:keys [cache-root] :as config}]
  (if (false? (:log config))
    []
    (let [log-config-path
          (path/resolve project-root cache-root "logging.properties")]

      (when-not (fs/existsSync log-config-path)
        (fs/writeFileSync log-config-path
          (str (util/slurp (path/resolve js/__dirname ".." "default-log.properties"))
               "\njava.util.logging.FileHandler.pattern=" (path/resolve cache-root "shadow-cljs.log") "\n")))

      [(str "-Djava.util.logging.config.file=" log-config-path)]
      )))

(defn run-standalone
  [project-root {:keys [cache-root source-paths jvm-opts] :as config} args]
  (let [aot-path
        (path/resolve project-root cache-root "aot-classes")

        aot-version-path
        (path/resolve aot-path "version.txt")

        ;; only aot compile when the shadow-cljs version changes
        ;; changing the version of a lib (eg. reagent) does not need a new AOT compile
        ;; actual shadow-cljs deps should only change when shadow-cljs version itself changes
        aot-compile?
        (if-not (fs/existsSync aot-version-path)
          true
          (let [aot-version (util/slurp aot-version-path)]
            (not= jar-version aot-version)))

        classpath
        (get-classpath project-root config)

        classpath-str
        (->> (:files classpath)
             (concat [aot-path])
             (concat source-paths)
             (str/join cp-seperator))

        cli-args
        (-> []
            (into jvm-opts)
            (into (logging-config project-root config))
            (cond->
              aot-compile? ;; FIXME: maybe try direct linking?
              (into [(str "-Dclojure.compile.path=" aot-path)]))
            (into ["-cp" classpath-str "clojure.main"])
            (cond->
              aot-compile?
              (into ["-e" "(require 'shadow.cljs.aot-helper)"]))
            (into ["-m" "shadow.cljs.devtools.cli"
                   "--npm"])
            (into args))]


    (mkdirp/sync aot-path)

    (when aot-compile?
      (println "shadow-cljs - re-building aot cache on startup, that will take some time.")
      (remove-class-files aot-path)
      (fs/writeFileSync aot-version-path jar-version))

    (println "shadow-cljs - starting ...")
    (run-java project-root cli-args {})))

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
  (println)
  )

(defn dump-script-state []
  (println "--- active requests")
  (prn (js/process._getActiveRequests))
  (println "--- active handles")
  (prn (js/process._getActiveHandles)))

(defn read-config [config-path opts]
  (try
    (-> (util/slurp config-path)
        (reader/read-string)
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
        (do (println "shadow-cljs not installed in project.")
            (println "")

            (if-not (rl-sync/keyInYN "Add it now?")
              false
              (let [[pkg-cmd pkg-args]
                    (case (guess-node-package-manager project-root config)
                      :yarn
                      ["yarn" ["add" "--dev" "shadow-cljs"]]
                      :npm
                      ["npm" ["install" "--save-dev" "shadow-cljs"]])]

                (println (str "Running: " pkg-cmd " " (str/join " " pkg-args)))

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

(defn ^:export main [args]

  ;; https://github.com/tapjs/signal-exit
  ;; without this the shadow-cljs process leaves orphan java processes
  ;; that do not exit when the node process is killed (by closing the terminal)
  ;; just adding this causes the java processes to exit properly ...
  ;; I do not understand why ... but I can still use spawnSync this way so I'll take it
  (let [onExit (js/require "signal-exit")]
    (onExit (fn [code signal])))

  (try
    (let [{:keys [action builds options summary errors] :as opts}
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
                  (read-config config-path opts)]

              (when (check-project-install! project-root config)

                (if (not (map? config))
                  (do (println "shadow-cljs - old config format no longer supported")
                      (println config-path)
                      (println "  previously a vector was used to define builds")
                      (println "  now {:builds the-old-vector} is expected"))

                  (let [{:keys [cache-root version] :as config}
                        (merge defaults config)

                        server-pid
                        (path/resolve project-root cache-root "cli-repl.port")]

                    (println "shadow-cljs - config:" config-path "version:" version)

                    (cond
                      (or (:cli-info options)
                          (= :info action))
                      (print-cli-info project-root config-path config opts)

                      (= :pom action)
                      (generate-pom project-root config-path config opts)

                      (fs/existsSync server-pid)
                      (client/run project-root config server-pid opts args)

                      (:lein config)
                      (run-lein project-root config args)

                      :else
                      (run-standalone project-root config args)
                      )))))))))
    (catch :default ex
      (print-error ex)
      (js/process.exit 1))))
