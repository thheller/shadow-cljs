(ns shadow.cljs.devtools.api
  (:refer-clojure :exclude (compile test))
  (:require
    [clojure.core.async :as async :refer (go <! >! >!! <!! alt! alt!!)]
    [clojure.java.io :as io]
    [clojure.java.browse :refer (browse-url)]
    [clojure.string :as str]
    [shadow.jvm-log :as log]
    [shadow.runtime.services :as rt]
    [shadow.build :as build]
    [shadow.build.api :as build-api]
    [shadow.build.npm :as npm]
    [shadow.build.classpath :as cp]
    [shadow.build.babel :as babel]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.cljs.devtools.server.util :as util]
    [shadow.cljs.devtools.server.common :as common]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.errors :as e]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.repl-impl :as repl-impl]
    [shadow.cljs.devtools.server.runtime :as runtime])
  (:import [java.net Inet4Address NetworkInterface]
           [java.io StringReader]))

;; nREPL support

(def ^:dynamic *nrepl-init* nil)

(defonce reload-deps-fn-ref (atom nil))

(defn reload-deps!
  ([] (reload-deps! {}))
  ([{:keys [deps ignore-conflicts] :as opts}]
   (let [fn @reload-deps-fn-ref]
     (if-not fn
       ::standalone-only
       (let [{:keys [conflicts new] :as result} (fn opts)]
         (cond
           (and (seq conflicts) (not (true? ignore-conflicts)))
           (do (doseq [{:keys [dep before after]} conflicts]
                 (println "Could not load:" dep (:mvn/version after) "version" (:mvn/version before) "is already loaded!"))
               ::conflicts)

           (seq new)
           (do (doseq [dep new]
                 (println "Loaded:" dep))
               ::loaded)

           :else
           ::no-changes
           ))))))

(defn get-worker
  [id]
  {:pre [(keyword? id)]}
  (let [{:keys [out supervisor] :as app}
        (runtime/get-instance!)]
    (super/get-worker supervisor id)
    ))

(defn make-runtime []
  (let [config (-> (config/load-cljs-edn!)
                   ;; just in case someone gets the idea to put :server-runtime true into their config
                   (dissoc :server-runtime))]

    (log/debug ::runtime-start)

    (-> {::started (System/currentTimeMillis)
         :config config}
        (rt/init (common/get-system-config config))
        (rt/start-all)
        )))

(defmacro with-runtime [& body]
  ;; not using binding since there should only ever be one runtime instance per JVM
  `(let [body-fn#
         (fn []
           ~@body)]
     (if (runtime/get-instance)
       (body-fn#)

       ;; start/stop instance when not running in server context
       (let [runtime# (make-runtime)]
         (try
           (runtime/set-instance! runtime#)
           (body-fn#)
           (finally
             (runtime/reset-instance!)
             (rt/stop-all runtime#)))))))

(defn get-build-config [build-id]
  (config/get-build build-id))

(defn get-build-ids []
  (-> (config/load-cljs-edn)
      (get :builds)
      (keys)
      (into #{})))

(defn get-runtime! []
  (runtime/get-instance!))

(defn- get-or-start-worker [build-config opts]
  (let [{:keys [supervisor] :as app}
        (runtime/get-instance!)]

    (if-let [worker (super/get-worker supervisor (:build-id build-config))]
      worker
      (super/start-worker supervisor build-config opts)
      )))

(defn- start-worker [build-config opts]
  (let [{:keys [supervisor] :as app}
        (runtime/get-instance!)]

    (super/start-worker supervisor build-config opts)
    ))

(defn worker-running? [build-id]
  (let [{:keys [supervisor] :as app}
        (runtime/get-instance!)]

    (contains? (super/active-builds supervisor) build-id)
    ))

(defn watch-compile!
  "manually trigger a recompile for a watch when {:autobuild false} is used"
  [build-id]
  (let [worker (get-worker build-id)]
    (if-not worker
      :watch-not-running
      (do (-> worker
              (worker/compile)
              (worker/sync!))
          ;; avoid returning the worker state because it will blow up the REPL
          :ok))))

(defn watch-compile-all!
  "call watch-compile! for all running builds"
  []
  (let [{:keys [supervisor]}
        (runtime/get-instance!)

        active-builds
        (super/active-builds supervisor)]

    (doseq [id active-builds]
      (watch-compile! id))

    :ok))

(defn watch-set-autobuild!
  "starts/stops autobuild for a watch worker by id
   (watch-set-autobuild :app true|false)"
  [build-id toggle]
  (let [worker (get-worker build-id)]
    (cond
      (nil? worker)
      :watch-not-running

      toggle
      (-> worker
          (worker/start-autobuild)
          (worker/sync!))

      :else
      (-> worker
          (worker/stop-autobuild)
          (worker/sync!)))
    :ok))

(defn watch*
  [{:keys [build-id] :as build-config}
   {:keys [verbose sync log-chan log-close?]
    :or {log-close? true}
    :as opts}]
  {:pre [(map? build-config)
         (keyword? build-id) ;; not required here but by start-worker
         (map? opts)]}
  (let [out (or log-chan (util/stdout-dump verbose))

        autobuild?
        (and (not (false? (:autobuild opts)))
             (not (false? (get-in build-config [:devtools :autobuild]))))]

    (-> (start-worker build-config opts)
        (worker/watch out log-close?)
        (cond->
          autobuild?
          (worker/start-autobuild)

          (not autobuild?)
          (worker/compile)

          (not (false? sync))
          (worker/sync!))
        )))

(defn watch
  "starts a dev worker process for a given :build-id
  opts defaults to {:autobuild true}"
  ([build-id]
   (watch build-id {}))
  ([build-id opts]
   (if (worker-running? build-id)
     :already-watching
     (let [build-config
           (if (map? build-id)
             build-id
             (config/get-build! build-id))]

       (watch* build-config opts)
       :watching
       ))))

(defn active-builds []
  (let [{:keys [supervisor]}
        (runtime/get-instance!)]
    (super/active-builds supervisor)))

(defn repl-runtimes
  "lists all connected REPL runtimes for a given build watch worker"
  [build-id]
  (when-let [worker (get-worker build-id)]
    (->> (-> worker :state-ref deref :runtimes vals)
         (map #(dissoc % :runtime-out :init-sent))
         (vec))))

(defn repl-runtime-select
  "switches to a specific REPL runtime to be used for eval"
  [build-id runtime-id]
  (when-let [{:keys [proc-control] :as worker} (get-worker build-id)]
    (>!! proc-control {:type :runtime-select :runtime-id runtime-id})))

(defn repl-runtime-kick
  "forcibly disconnects a connected REPL runtime"
  [build-id runtime-id]
  (when-let [{:keys [proc-control] :as worker} (get-worker build-id)]
    (>!! proc-control {:type :runtime-kick :runtime-id runtime-id})))

(defn repl-runtime-clear
  "kick all registered runtimes that haven't responded to ping within 5sec

   only needed in cases where the runtime doesn't properly disconnect which
   is currently the case for reloading a react-native app on android"
  []
  (doseq [build-id (active-builds)
          {:keys [runtime-id last-ping last-pong] :as repl-runtime} (repl-runtimes build-id)
          :let [diff (- last-ping last-pong)]
          :when (> diff 5000)]
    (repl-runtime-kick build-id runtime-id)))

(defn compiler-env [build-id]
  (let [{:keys [supervisor]}
        (runtime/get-instance!)]
    (when-let [worker (super/get-worker supervisor build-id)]
      (-> worker
          :state-ref
          (deref)
          :build-state
          :compiler-env))))

(comment
  (watch :browser)
  (stop-worker :browser))

(defn stop-worker [build-id]
  (let [{:keys [supervisor] :as app}
        (runtime/get-instance!)]
    (super/stop-worker supervisor build-id)
    :stopped))

(defn get-config []
  (or (when-let [inst (runtime/get-instance)]
        (:config inst))
      (config/load-cljs-edn)))

(defn get-build-config [id]
  {:pre [(keyword? id)]}
  (config/get-build! id))

(defn build-finish [{::build/keys [build-info] :as state} config]
  (util/print-build-complete {:info build-info :build-id (:build-id config)})
  (let [rt (::runtime state)]
    (when (::once rt)
      (let [{:keys [babel npm]} rt]
        (babel/stop babel)
        (npm/stop npm))))
  state)

(defn compile* [build-config opts]
  (util/print-build-start build-config)
  (-> (util/new-build build-config :dev opts)
      (build/configure :dev build-config opts)
      (build/compile)
      (build/flush)
      (build-finish build-config)))

(defn compile!
  "do not use at the REPL, will return big pile of build state, will blow up REPL by printing too much"
  [build opts]
  (with-runtime
    (let [build-config (config/get-build! build)]
      (compile* build-config opts))))

(defn compile
  ([build]
   (compile build {}))
  ([build opts]
   (try
     (compile! build opts)
     :done
     (catch Exception e
       (e/user-friendly-error e)))))

(defn once
  "deprecated: use compile"
  ([build]
   (compile build {}))
  ([build opts]
   (compile build opts)))

(defn release*
  [build-config {:keys [debug source-maps pseudo-names] :as opts}]
  (util/print-build-start build-config)
  (-> (util/new-build build-config :release opts)
      (build/configure :release build-config opts)
      (cond->
        (or debug source-maps)
        (build-api/enable-source-maps)

        (or debug pseudo-names)
        (build-api/with-compiler-options
          {:dump-closure-inputs true
           :pretty-print true
           :pseudo-names true}))
      (build/compile)
      (build/optimize)
      (build/flush)
      (build-finish build-config)))

(defn release!
  ([build]
   (release! build {}))
  ([build opts]
   (with-runtime
     (let [build-config (config/get-build! build)]
       (release* build-config opts)))
   :done))

(defn release
  ([build]
   (release build {}))
  ([build opts]
   (try
     (with-runtime
       (let [build-config (config/get-build! build)]
         (release* build-config opts)))
     :done
     (catch Exception e
       (e/user-friendly-error e)))))

(defn check* [{:keys [id] :as build-config} opts]
  ;; FIXME: pretend release mode so targets don't need to account for extra mode
  ;; in most cases we want exactly :release but not sure that is true for everything?
  (-> (util/new-build build-config :release opts)
      (build/configure :release build-config opts)
      (as-> {:keys [cache-dir] :as X}
        (assoc X
          ;; using another dir because of source maps
          ;; not sure :release builds want to enable source maps by default
          ;; so running check on the release dir would cause a recompile which is annoying
          ;; but check errors are really useless without source maps
          :cache-dir (io/file cache-dir "check")
          ;; always override :output-dir since check output should never be used
          ;; only generates output for source maps anyways
          :output-dir (io/file cache-dir "check-out")))
      (build-api/enable-source-maps)
      (update-in [:compiler-options :closure-warnings] merge {:check-types :warning})
      (build/compile)
      (build/check)
      (build-finish build-config))
  :done)

(defn check
  ([build]
   (check build {}))
  ([build opts]
   (try
     (with-runtime
       (let [build-config (config/get-build! build)]
         (check* build-config opts)))
     (catch Exception e
       (e/user-friendly-error e)))))

(defn nrepl-select
  ([id]
   (nrepl-select id {}))
  ([id opts]
   (cond
     (nil? *nrepl-init*)
     :missing-nrepl-middleware

     :else
     (do (*nrepl-init* id opts)
         ;; Cursive uses this to switch repl type to cljs
         (println "To quit, type: :cljs/quit")
         [:selected id]))))

(defn select-cljs-runtime [worker]
  (let [all (-> worker :state-ref deref :runtimes vals vec)]

    (println (format "There are %d connected runtimes, please select one by typing the number and pressing enter." (count all)))
    (println "Type x or q to quit")

    (doseq [[idx runtime] (->> all
                               (map :runtime-info)
                               (map-indexed vector))]
      (prn [idx runtime]))


    (let [num (read)]
      (cond
        (number? num)
        (get all num)

        (= num 'x)
        {:quit true}

        (= num 'q)
        {:quit true}

        :else
        (recur worker)
        ))))

(defn repl
  ([build-id]
   (repl build-id {}))
  ([build-id {:keys [stop-on-eof] :as opts}]
   (if *nrepl-init*
     (nrepl-select build-id opts)
     (let [{:keys [supervisor] :as app}
           (runtime/get-instance!)

           worker
           (super/get-worker supervisor build-id)]
       (if-not worker
         :no-worker
         (do (repl-impl/stdin-takeover! worker app)
             (when stop-on-eof
               (super/stop-worker supervisor build-id))))))))

;; FIXME: should maybe allow multiple instances
(defn node-repl
  ([]
   (node-repl {}))
  ([{:keys [build-id] :or {build-id :node-repl} :as opts}]
   {:pre [(map? opts)]}
   (let [{:keys [supervisor] :as app}
         (runtime/get-instance!)

         was-running?
         (worker-running? build-id)

         worker
         (or (super/get-worker supervisor build-id)
             (repl-impl/node-repl* app opts))]

     (repl build-id {:skip-repl-out (not was-running?)})
     )))

;; FIXME: should maybe allow multiple instances
(defn start-browser-repl*
  [{:keys [config supervisor] :as app}
   {:keys [build-id] :or {build-id :browser-repl} :as opts}]
  (let [cfg
        {:build-id build-id
         :target :browser
         :output-dir (str (:cache-root config) "/builds/" (name build-id) "/js")
         :asset-path (str "/cache/" (name build-id) "/js")
         :modules
         {:repl {:entries '[shadow.cljs.devtools.client.browser-repl]}}
         :devtools
         {:autoload false}}]

    (super/start-worker supervisor cfg {})))

(defn browser-repl
  ([]
   (browser-repl {}))
  ([{:keys [verbose open build-id]
     :or {build-id :browser-repl}
     :as opts}]
   (let [{:keys [supervisor http] :as app} (runtime/get-instance!)]

     (or (when-let [{:keys [state-ref] :as worker}
                    (super/get-worker supervisor build-id)]

           (if-not (-> @state-ref :runtimes empty?)
             worker ;; browser still connected. continue using previous worker.
             (do (super/stop-worker supervisor build-id)
                 nil)))

         ;; no previous worker, start new one
         (let [worker
               (start-browser-repl* app opts)

               out-chan
               (-> (async/sliding-buffer 10)
                   (async/chan))]

           (go (loop []
                 (when-some [msg (<! out-chan)]
                   (try
                     (util/print-worker-out msg verbose)
                     (catch Exception e
                       (prn [:print-worker-out-error e])))
                   (recur)
                   )))

           (worker/watch worker out-chan)
           (worker/compile! worker)

           (let [url (str "http" (when (:ssl http) "s") "://localhost:" (:port http) "/repl-js/" (name build-id))]
             (try
               (browse-url url)
               (catch Exception e
                 (println
                   (format "Failed to open Browser automatically.\nPlease open the URL below in your Browser:\n\t%s" url)))))
           worker))

     (repl build-id)
     )))

(defn dev*
  [{:keys [build-id] :as build-config} {:keys [autobuild verbose] :as opts}]
  (if (worker-running? build-id)
    :already-watching
    (let [config
          (config/load-cljs-edn)

          {:keys [supervisor] :as app}
          (runtime/get-instance!)

          out
          (util/stdout-dump verbose)

          worker
          (-> (start-worker build-config opts)
              (worker/watch out false)
              (cond->
                (not (false? autobuild))
                (-> (worker/start-autobuild)
                    (worker/sync!))))]

      ;; for normal REPL loops we wait for the CLJS loop to end
      (when-not *nrepl-init*
        (repl-impl/stdin-takeover! worker app)
        (super/stop-worker supervisor (:build-id build-config)))

      :done)))

(defn dev
  ([build]
   (dev build {}))
  ([build opts]
   (try
     (let [build-config (config/get-build! build)]
       (dev* build-config opts))
     (catch Exception e
       (e/user-friendly-error e)))))

(defn help []
  (-> (slurp (io/resource "shadow/txt/repl-help.txt"))
      (println)))

(defn find-resources-using-ns [ns]
  (let [{:keys [classpath]} (get-runtime!)]
    (->> (cp/find-resources-using-ns classpath ns)
         (map :ns)
         (into #{}))))

(defn test []
  (println "TBD"))

(defn find-local-addrs []
  (for [ni (enumeration-seq (NetworkInterface/getNetworkInterfaces))
        :when (not (.isLoopback ni))
        :when (.isUp ni)
        :let [display-name (.getDisplayName ni)]
        :when
        (and (not (str/includes? display-name "VirtualBox"))
             (not (str/includes? display-name "vboxnet"))
             (not (str/includes? display-name "utun"))
             (not (re-find #"tun\d+" display-name)))
        addr (enumeration-seq (.getInetAddresses ni))
        ;; probably don't need ipv6 for dev
        :when (instance? Inet4Address addr)]
    [ni addr]))

(defmethod log/log-msg ::multiple-ips [_ {:keys [addrs] :as data}]
  (str "Found multiple IPs, might be using the wrong one. Please report all interfaces should the chosen one be incorrect.\n"
       (->> (for [[ni addr] addrs]
              (format "Found IP:%s Interface:%s" (.getHostAddress addr) (.getDisplayName ni)))
            (str/join "\n"))))

(defn get-server-addr []
  (let [addrs (find-local-addrs)]
    (when (seq addrs)
      (let [[ni addr] (first addrs)]

        (comment
          (println (format "shadow-cljs - Using IP \"%s\" from Interface \"%s\"" (.getHostAddress addr) (.getDisplayName ni)))

          (when (not= 1 (count addrs))
            (log/info ::multiple-ips {:addrs addrs})))

        ;; would be neat if we could just use InetAddress/getLocalHost
        ;; but that returns my VirtualBox Adapter for some reason
        ;; which is incorrect and doesn't work
        (.getHostAddress addr)))))

;; FIXME: figure out which other opts this should take and document properly
;; {:ns some-sym} for setting the initial ns
(defn cljs-eval
  [build-id code opts]
  {:pre [(keyword? build-id)
         (string? code)
         (map? opts)]}

  (let [worker
        (get-worker build-id)

        {:keys [relay]}
        (get-runtime!)

        init-ns
        (:ns opts 'cljs.user)

        results-ref
        (atom {:results []
               :out ""
               :err ""
               :ns init-ns})]

    ;; FIXME: should this throw if worker isn't running?
    (when worker
      (repl-impl/do-repl
        worker
        relay
        (StringReader. code)
        (async/chan)
        {:init-state
         {:ns init-ns}

         :repl-prompt
         (fn repl-prompt [repl-state])

         :repl-read-ex
         (fn repl-read-ex [repl-state ex]
           (swap! results-ref assoc :read-ex ex))

         :repl-result
         (fn repl-result [{:keys [ns] :as repl-state} result-as-printed-string]
           (swap! results-ref assoc :ns ns)
           (when-not (nil? result-as-printed-string)
             (swap! results-ref update :results conj result-as-printed-string)))

         :repl-stderr
         (fn repl-stderr [repl-state text]
           (swap! results-ref update :err str text))

         :repl-stdout
         (fn repl-stdout [repl-state text]
           (swap! results-ref update :out str text))
         })

      @results-ref)))