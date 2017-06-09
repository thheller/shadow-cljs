(ns shadow.cljs.npm.cli
  (:require-macros [cljs.core.async.macros :refer (go go-loop)])
  (:require ["path" :as path]
            ["fs" :as fs]
            ["child_process" :as cp]
            ["readline-sync" :as rl]
            ["mkdirp" :as mkdirp]
            [cljs.reader :as reader]
            [cljs.core.async :as async]
            [clojure.string :as str]
            [shadow.cljs.devtools.remote.client :as client]
            [shadow.cljs.npm.terminal :as terminal]))

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

(defn run [project-root java-cmd java-args]
  (cp/spawnSync java-cmd (into-array java-args) #js {:stdio "inherit" :cwd project-root}))

(defn run-java [project-root args]
  (let [result (run project-root "java" args)]

    (if (zero? (.-status result))
      true
      (when (and (.-error result) (= "ENOENT" (.. result -error -errno)))

        (js/console.log "shadow-cljs - java not found, trying node-jre")

        (let [jre
              (js/require "node-jre")

              result
              (run project-root (.driver jre) args)]

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
    (run project-root "lein" lein-args)))

(def default-config-str
  (slurp (path/resolve js/__dirname "default-config.edn")))

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

          (when (rl/keyInYN "Create one?")
            ;; FIXME: ask for default source path, don't just use one
            (fs/writeFileSync config default-config-str)
            (println "shadow-cljs - created default configuration")
            config
            ))))))

(defn modified-dependencies? [cp-file config]
  (let [cp (-> (slurp cp-file)
               (reader/read-string))]

    (or (not= (:version cp) (:version config))
        (not= (:dependencies cp) (:dependencies config))
        )))

(defn get-classpath [project-root {:keys [cache-root] :as config}]
  (let [cp-file (path/resolve project-root cache-root "classpath.edn")]

    ;; only need to rebuild the classpath if :dependencies
    ;; or the version changed
    (when (or (not (fs/existsSync cp-file))
              (modified-dependencies? cp-file config))

      ;; re-create classpath by running the java helper
      (let [jar (js/require "shadow-cljs-jar/path")]
        (run-java project-root ["-jar" jar version (path/resolve project-root "shadow-cljs.edn")])))

    ;; only return :files since the rest is just cache info
    (-> (slurp cp-file)
        (reader/read-string)
        (:files)
        )))

;; FIXME: windows uses ;
(def cp-seperator ":")

(defn aot-compile [project-root aot-path classpath]
  (let [version-file (path/resolve aot-path "version")]

    ;; FIXME: is it enough to AOT only when versions change?
    (when (or (not (fs/existsSync version-file))
              (not= version (slurp version-file)))

      (mkdirp/sync aot-path)

      (print "shadow-cljs - optimizing startup")

      (run-java
        project-root
        ["-cp" classpath
         ;; FIXME: maybe try direct linking?
         (str "-Dclojure.compile.path=" aot-path)
         "clojure.main"
         "-e" "(run! compile '[shadow.cljs.devtools.api shadow.cljs.devtools.server shadow.cljs.devtools.cli])"])

      (fs/writeFileSync version-file version)
      )))

(defn run-standalone
  [project-root {:keys [cache-root source-paths] :as config} args]
  (let [aot-path
        (path/resolve project-root cache-root "aot-classes")

        classpath
        (->> (get-classpath project-root config)
             (concat [aot-path])
             (concat source-paths)
             (str/join cp-seperator))

        cli-args
        (into ["-cp" classpath "shadow.cljs.devtools.cli" "--npm"] args)]

    (aot-compile project-root aot-path classpath)

    (println "shadow-cljs - starting ...")
    (run-java project-root cli-args)
    ))

(def defaults
  {:cache-root "target/shadow-cljs"
   :version version
   :dependencies []})

(defn remote-active? [{:keys [cache-root] :as config}]
  (let [pid-file (path/resolve cache-root "remote.pid")]
    (fs/existsSync pid-file)))


(defmulti process-call-result
  (fn [state call call-args result]
    call)
  :default
  ::unknown)

(defmulti process-notify
  (fn [state msg]
    (:method msg))
  :default
  ::unknown)

(defmethod process-notify ::unknown
  [state msg]
  (prn [:unhandled-notify msg])
  state)

(defmethod process-call-result ::unknown
  [state call call-args result]
  (prn [:unhandled-call-result call call-args result])
  state)

(defmethod process-call-result "cljs/hello"
  [state call call-args {:keys [builds supervisor] :as result}]
  (doseq [[k v] supervisor]
    (prn [:super k v]))
  (assoc state :builds builds :workers workers))

(defn remote-process-in
  [{:keys [encoding] :as state}
   {:keys [headers body] :as in}]
  ;; (prn [:in in])
  (let [content-type
        (get headers "content-type")

        {:keys [id] :as msg}
        (reader/read-string body)]

    (if (nil? id)
      (process-notify state msg)
      (let [[call-method call-args :as x] (get-in state [:remote :pending id])]
        (prn [:handle id call-method call-args])
        (if (nil? x)
          (prn [:no-handler-for-result id state])

          (-> state
              (update-in [:remote :pending] dissoc id)
              (process-call-result call-method call-args (:result msg)))
          )))))

(defn remote-call [{:keys [remote] :as state} method & params]
  (let [{:keys [id-seq]} remote

        params
        (into [] params)

        msg ;; FIXME: actual content-type from client state
        {:headers {"content-type" "application/edn"}
         :body (pr-str {:method method :params params :id id-seq})}]

    (if-not (async/offer! (:output remote) msg)
      (prn [:failed-to-remote-call method params (:output remote)])
      (-> state
          (update-in [:remote :id-seq] inc)
          (update-in [:remote :pending] assoc id-seq [method params]))
      )))

(defn remote-loop [{:keys [remote] :as state}]
  (let [{remote-input :input}
        remote

        state
        (remote-call state "cljs/hello")]

    ;; FIXME: should the client ask for things are just assume the server sends it?

    (go-loop [state state]
      (terminal/render state)

      (when-some [msg (<! remote-input)]
        (when-let [next-state (remote-process-in state msg)]
          (recur next-state))
        ))))


(defn run-remote [project-root config args]
  (println "shadow-cljs - remote mode")

  (let [remote-in
        (async/chan 10)

        remote-out
        (async/chan 10)

        connect
        (client/connect
          {:host "localhost"
           :port 8201
           :in remote-in
           :out remote-out})

        init-state
        (-> {:remote
             {:input remote-in
              :output remote-out
              :id-seq 0
              :pending {}}
             :builds {}
             :warnings {}}
            (terminal/setup))]

    (go (if-not (<! connect)
          (println "shadow-cljs - remote connect failed")

          (do (<! (remote-loop init-state))
              (println "shadow-cljs - remote loop end"))
          ))))


(defn main [& args]
  (when-let [config-path (ensure-config)]
    (println "shadow-cljs -" version "using" config-path)

    (let [project-root
          (path/dirname config-path)

          config
          (-> (slurp config-path)
              (reader/read-string))]

      (if (not (map? config))
        (do (println "shadow-cljs - old config format no longer supported")
            (println "  previously a vector was used to define builds")
            (println "  now {:builds the-old-vector} is expected"))

        (let [config (merge defaults config)]
          (cond
            (remote-active? config)
            (run-remote project-root config args)

            (:lein config)
            (run-lein project-root config args)

            :else
            (run-standalone project-root config args)
            ))))))
