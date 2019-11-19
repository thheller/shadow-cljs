(ns shadow.cljs.devtools.server
  (:require
    [clojure.core.async :as async :refer (thread go <!)]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [shadow.jvm-log :as log]
    [shadow.http.router :as http]
    [shadow.runtime.services :as rt]
    [shadow.undertow :as undertow]
    [shadow.build :as sb]
    [shadow.build.classpath :as build-classpath]
    [shadow.cljs.model :as m]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.devtools.server.config-watch :as config-watch]
    [shadow.cljs.devtools.server.fs-watch :as fs-watch]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.common :as common]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server.repl-system :as repl-system]
    [shadow.cljs.devtools.server.prepl :as prepl]
    [shadow.cljs.devtools.server.ns-explorer :as ns-explorer]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.cljs.devtools.server.util :as util]
    [shadow.cljs.devtools.server.socket-repl :as socket-repl]
    [shadow.cljs.devtools.server.runtime :as runtime]
    [shadow.cljs.devtools.server.dev-http :as dev-http]
    [shadow.cljs.devtools.server.reload-classpath :as reload-classpath]
    [shadow.cljs.devtools.server.reload-npm :as reload-npm]
    [shadow.cljs.devtools.server.reload-macros :as reload-macros]
    [shadow.cljs.devtools.server.build-history :as build-history]
    [shadow.remote.relay.local :as relay]
    [shadow.remote.runtime.clojure :as clj-runtime]
    [shadow.remote.runtime.obj-support :as obj-support]
    [shadow.remote.runtime.tap-support :as tap-support]
    [shadow.remote.runtime.eval-support :as eval-support]
    [shadow.cljs.devtools.server.system-bus :as system-bus]
    [shadow.cljs.devtools.server.system-bus :as sys-bus])
  (:import (java.net BindException Socket SocketException InetSocketAddress)
           [java.lang.management ManagementFactory]
           [java.util UUID]))

(defn wait-for [app-ref]
  (or @app-ref
      (loop [x 0]
        (Thread/sleep 100)
        (or @app-ref
            (if (> x 10)
              ::timeout
              (recur (inc x)))))))

;; delay loading the web namespace since it has a bunch of dependencies
;; that take a bit of time to load but are only relevant when someone
;; actually accesses the webserver. also skips generating AOT classes
;; which we don't really want for pathom+ring stuff anyways
(def require-web-ns
  (delay
    ;; locking so that dynamically loading build targets can't happen while this is still loading
    ;; should prevent a race condition where `shadow-cljs watch browser-test-build` would try
    ;; to use hiccup.page while its still loading
    (locking sb/target-require-lock
      (require 'shadow.cljs.devtools.server.web))))

(defn get-ring-handler [app-ref]
  (let [web-root-var
        (delay
          @require-web-ns
          (find-var 'shadow.cljs.devtools.server.web/root))]

    (fn [ring-map]
      (let [app (wait-for app-ref)]
        (if (= app ::timeout)
          {:status 501
           :body "App not ready!"}
          (-> app
              (assoc :ring-request ring-map)
              (http/prepare)
              (@web-root-var)))))))

(defn get-ring-middleware [config handler]
  (let [middleware-fn-delay
        (delay
          @require-web-ns
          (let [factory (find-var 'shadow.cljs.devtools.server.web/get-ring-middleware)]
            (factory config handler)))]

    (fn [ring-map]
      (@middleware-fn-delay ring-map))))

;; println may fail if the socket already disconnected
;; just discard the print if it fails
(defn discard-println [msg]
  (try
    (println msg)
    (catch Exception ignored)))

(defmacro do-shutdown [& body]
  `(try
     ~@body
     (catch Throwable t#
       (log/warn-ex t# ::shutdown-ex {:form ~(pr-str body)})
       (discard-println ~(str "shutdown failed: " (pr-str body))))))

(defn shutdown-system [{:keys [shutdown-hook http port-files-ref socket-repl cli-repl cli-checker nrepl] :as app}]
  (discard-println "shutting down ...")
  (try
    (. (Runtime/getRuntime) (removeShutdownHook shutdown-hook))
    (catch IllegalStateException e
      ;; can't remove the hook while running the hook
      ))

  (do-shutdown
    (doseq [port-file (vals @port-files-ref)]
      (.delete port-file)))

  (when socket-repl
    (do-shutdown (socket-repl/stop socket-repl)))

  (do-shutdown (socket-repl/stop cli-repl))

  (when nrepl
    (let [stop (::stop nrepl)]
      (do-shutdown (stop))))

  (when cli-checker
    ;; CompletableFuture
    (.cancel cli-checker true))

  (do-shutdown (undertow/stop (:server http)))

  (do-shutdown (rt/stop-all app))

  #_(discard-println "shutdown complete."))

(defn make-port-files [cache-root ports]
  (io/make-parents (io/file cache-root "foo.txt"))

  (reduce-kv
    (fn [result key port]
      (assert (keyword? key))
      (if-not (pos-int? port)
        result
        (let [port-file
              (doto (io/file cache-root (str (name key) ".port"))
                (.deleteOnExit))]
          (spit port-file (str port))
          (assoc result key port-file))))
    {}
    ports))

(defmethod log/log-msg ::tcp-port-unavailable [_ {:keys [port]}]
  (format "TCP Port %d in use." port))

(defn start-http [config {:keys [port strict] :as http-config} app-ref]
  (let [ring-fn
        (get-ring-handler app-ref)

        ui-root
        (io/file ".shadow-cljs" "ui")

        req-handler
        [::undertow/classpath {:root "shadow/cljs/ui/dist"}
         [::undertow/classpath {:root "shadow/cljs/devtools/server/web/resources"}
          [::undertow/blocking
           [::undertow/ring {:handler-fn ring-fn}]]]]

        req-handler
        (if-not (.exists ui-root)
          req-handler
          [::undertow/file {:root-dir ui-root} req-handler])

        handler-config
        [::undertow/soft-cache
         [::undertow/ws-upgrade
          [::undertow/ws-ring {:handler-fn ring-fn}]
          [::undertow/compress {}
           req-handler]]]]

    (loop [port (or port 9630)]
      (let [srv
            (try
              (undertow/start
                (assoc http-config :port port)
                handler-config)
              (catch Exception e
                (cond
                  strict
                  (throw e)

                  (instance? BindException (.getCause e))
                  (log/warn ::tcp-port-unavailable {:port port})

                  :else
                  (do (log/warn-ex e ::http-startup-ex)
                      (throw e)))))]

        (or srv (recur (inc port)))
        ))))

(declare stop!)

(defmethod log/log-msg ::nrepl-fallback [_ _]
  "Using tools.nrepl 0.2.* server!")

(defn start-system
  [app-ref app-config {:keys [cache-root] :as config}]
  (when @app-ref
    (throw (ex-info "app-ref not nil" {:app-ref app-ref})))

  (let [{:keys [http ssl]}
        config

        pid
        (-> (ManagementFactory/getRuntimeMXBean)
            (.getName)
            (str/split #"@")
            (first)
            (Long/valueOf))

        {:keys [ssl-context] :as http-config}
        (-> {:host "0.0.0.0"}
            (merge http)
            (cond->
              ssl
              (assoc :ssl-context (undertow/make-ssl-context ssl))))

        {:keys [http-port https-port] :as http}
        (start-http config http-config app-ref)

        socket-repl-config
        (:socket-repl config)

        socket-repl
        (when-not (false? socket-repl-config)
          (try
            (socket-repl/start socket-repl-config app-ref)
            (catch Exception e
              (log/warn-ex e ::socket-repl-ex)
              nil
              )))

        cli-repl-config
        {:port 0 ;; random port, not for humans
         :prompt false
         :print false}

        ;; remote entry point for the CLI tool that just sends one command
        ;; and waits for the socket to close, not using the normal socket REPL
        ;; because that prints/prompts for humans
        cli-repl
        (socket-repl/start cli-repl-config app-ref)

        cli-checker
        (when-let [cli-pid (System/getenv "SHADOW_CLI_PID")]
          (try
            ;; 9+ only, fails with ClassNotFoundException otherwise
            (Class/forName "java.lang.ProcessHandle")
            ;; separate namespace so it can still run in jdk1.8
            (let [attach-fn (requiring-resolve 'shadow.cljs.devtools.server.cli-check/attach)]
              (log/debug ::clj-check {:cli-pid cli-pid})
              (attach-fn cli-pid))
            (catch ClassNotFoundException e
              ;; FIXME: should probably still do something ...
              ;; checking a socket failed on some systems
              ;; checking a file failed on some systems
              ;; none of it reproducible, hope ProcessHandle is more reliable
              )))

        disable-nrepl?
        (or (false? (:nrepl config))
            (false? (get-in config [:system-config :nrepl])))

        nrepl
        (when-not disable-nrepl?
          (try
            (let [nrepl-ns
                  'shadow.cljs.devtools.server.nrepl

                  _ (require nrepl-ns)

                  nrepl-start
                  (ns-resolve nrepl-ns 'start)

                  nrepl-stop
                  (ns-resolve nrepl-ns 'stop)

                  server
                  (nrepl-start (:nrepl config))]

              ;; return a generic stop fn
              (assoc server ::stop #(nrepl-stop server)))
            (catch Exception e
              (log/warn-ex e ::nrepl-ex)
              nil)))

        ;; prepl
        ;; FIXME: this integration is kinda dirty
        ;; probably should only start servers when build is actually running?
        app-config
        (if-not (:prepl config)
          app-config
          (let [prepl-svc
                {:depends-on [:repl-system]
                 :start (fn [repl-system]
                          (let [svc (prepl/start repl-system)]
                            (doseq [[build-id port] (:prepl config)]
                              (prepl/start-server svc build-id (if (map? port) port {:port port})))
                            svc))
                 :stop prepl/stop}]
            (assoc app-config :prepl prepl-svc)))

        port-files-ref
        (atom nil)

        shutdown-hook
        (Thread.
          (fn []
            (println "Running shutdown hook.")
            (stop!)))

        app
        (-> {::started (System/currentTimeMillis)
             :server-pid pid
             :server-secret (str (UUID/randomUUID))
             :config config
             :shutdown-hook shutdown-hook
             :ssl-context ssl-context
             :cli-checker cli-checker
             :http {:port (or https-port http-port)
                    :http-port http-port
                    :https-port https-port
                    :host (:host http-config)
                    :ssl (boolean https-port)
                    :server http}
             :port-files-ref port-files-ref
             :cli-repl cli-repl}
            (cond->
              socket-repl
              (assoc :socket-repl socket-repl)
              nrepl
              (assoc :nrepl nrepl))
            (rt/init app-config)
            (rt/start-all))

        pid-file
        (doto (io/file cache-root "server.pid")
          (.deleteOnExit))]

    (vreset! app-ref app)

    ;; do this as the very last setup to maybe fix circleci timing issue?
    (reset! port-files-ref
      (make-port-files cache-root
        (-> {:cli-repl (:port cli-repl)
             :http http-port
             :https-port https-port}
            (cond->
              nrepl
              (assoc :nrepl (:port nrepl))
              socket-repl
              (assoc :socket-repl (:port socket-repl)))
            )))

    ;; this will clash with lein writing its own .nrepl-port so it is disabled by default
    (when (and nrepl
               (or (get-in config [:nrepl :write-port-file])
                   (get-in config [:system-config :nrepl :write-port-file])))
      (let [nrepl-port-file (io/file ".nrepl-port")]
        (spit nrepl-port-file (str (:port nrepl)))
        (swap! port-files-ref assoc :nrepl-port nrepl-port-file)
        (.deleteOnExit nrepl-port-file)))

    (. (Runtime/getRuntime) (addShutdownHook shutdown-hook))

    (spit pid-file pid)

    app-ref))

(defn load-config []
  (-> (config/load-cljs-edn)
      ;; system config doesn't need build infos
      (dissoc :builds)))

(defn start!
  ([]
   (let [config (load-config)]
     (start! config)))
  ([sys-config]
   (if (runtime/get-instance)
     ::already-running
     (do (log/set-level! (or (get-in sys-config [:log :level])
                             (get-in sys-config [:user-config :log :level])
                             :info))
         (let [app
               (merge
                 {:dev-http
                  {:depends-on [:system-bus :config :ssl-context :out]
                   :start dev-http/start
                   :stop dev-http/stop}

                  :system-bus
                  {:depends-on []
                   :start system-bus/start
                   :stop system-bus/stop}

                  :cljs-watch
                  {:depends-on [:config :classpath :system-bus]
                   :start (fn [config classpath system-bus]
                            (fs-watch/start
                              (:fs-watch config)
                              (->> (build-classpath/get-classpath-entries classpath)
                                   (filter #(.isDirectory %))
                                   (into []))
                              ;; no longer watches .clj files, reload-macros directly looks at used macros
                              ["cljs" "cljc" "js"]
                              #(system-bus/publish! system-bus ::m/cljs-watch {:updates %})
                              ))
                   :stop fs-watch/stop}

                  :config-watch
                  {:depends-on [:system-bus]
                   :start config-watch/start
                   :stop config-watch/stop}

                  :ns-explorer
                  {:depends-on [:config :npm :babel :classpath :build-executor]
                   :start ns-explorer/start
                   :stop ns-explorer/stop}

                  :reload-classpath
                  {:depends-on [:system-bus :classpath]
                   :start reload-classpath/start
                   :stop reload-classpath/stop}

                  :build-history
                  {:depends-on [:system-bus]
                   :start build-history/start
                   :stop build-history/stop}

                  :reload-macros
                  {:depends-on [:system-bus]
                   :start reload-macros/start
                   :stop reload-macros/stop}

                  :supervisor
                  {:depends-on [:config :system-bus :build-executor :relay :cache-root :http :classpath :npm :babel]
                   :start super/start
                   :stop super/stop}

                  :repl-system
                  {:depends-on []
                   :start repl-system/start
                   :stop repl-system/stop}

                  :relay
                  {:depends-on []
                   :start relay/start
                   :stop relay/stop}

                  :clj-runtime
                  {:depends-on [:relay]
                   :start clj-runtime/start
                   :stop clj-runtime/stop}

                  :clj-runtime-obj-support
                  {:depends-on [:clj-runtime]
                   :start obj-support/start
                   :stop obj-support/stop}

                  :clj-runtime-tap-support
                  {:depends-on [:clj-runtime :clj-runtime-obj-support]
                   :start tap-support/start
                   :stop tap-support/stop}

                  :clj-runtime-eval-support
                  {:depends-on [:clj-runtime :clj-runtime-obj-support]
                   :start eval-support/start
                   :stop eval-support/stop}


                  :out
                  {:depends-on [:config]
                   :start (fn [config]
                            (util/stdout-dump (:verbose config)))
                   :stop async/close!}}
                 (common/get-system-config (assoc sys-config :server-runtime true)))

               app-ref
               (start-system runtime/instance-ref app sys-config)

               {:keys [http ssl-context socket-repl nrepl config] :as app}
               @app-ref

               http-host
               (let [host (:host http)]
                 (if (= host "0.0.0.0")
                   "localhost"
                   host))

               version
               (util/find-version)]

           (log/debug ::start)

           ;; require the web stuff async
           (future @require-web-ns)

           (println (str "shadow-cljs - server version: "
                         version
                         " running at http" (when ssl-context "s") "://" http-host ":" (:port http)))
           #_(println (str "shadow-cljs - socket REPL running on port " (:port socket-repl)))
           ;; must keep this message since cider looks for it
           (when nrepl
             (println (str "shadow-cljs - nREPL server started on port " (:port nrepl))))

           ::started
           )))))

(defn stop! []
  (when-let [inst @runtime/instance-ref]
    (shutdown-system inst)
    (runtime/reset-instance!)
    ))

(defn reload! []
  (when-let [inst @runtime/instance-ref]
    (let [new-inst
          (-> inst
              (rt/stop-all)
              (rt/start-all))]

      (vreset! runtime/instance-ref new-inst)
      ::restarted
      )))

(defn remote-stop! []
  (stop!)
  (shutdown-agents))

(defn wait-for-stop! []
  (loop []
    (when (some? @runtime/instance-ref)
      (Thread/sleep 250)
      (recur))))

(defn -main [& args]
  (start!)
  (wait-for-stop!)
  (shutdown-agents))

(defn wait-for-eof! []
  (loop []
    (let [x (try
              (read *in* false ::eof)
              (catch Exception e
                ::eof))]
      (when (not= x ::eof)
        (recur)))))

(defn watch-builds [config build-configs options]
  (let [{:keys [supervisor] :as app}
        @runtime/instance-ref

        out-chan
        (-> (async/sliding-buffer 100)
            (async/chan))

        verbose
        (or (:verbose options)
            (:verbose config))

        {:keys [supervisor] :as app}
        @runtime/instance-ref]

    (doseq [{:keys [build-id] :as build-config} build-configs]
      (println "shadow-cljs - watching build" build-id)
      (api/watch* build-config (assoc options :sync false)))

    (go (loop []
          (when-some [msg (<! out-chan)]
            (try
              (util/print-worker-out msg verbose)
              (catch Exception e
                (prn [:print-worker-out-error e])))
            (recur)
            )))
    ))

(defn stdin-closed? []
  (let [avail (.available System/in)]
    (if (= -1 avail)
      true
      (do (let [buf (byte-array avail)]
            (.read System/in buf))
          false))))

(defn from-cli [action builds {:keys [verbose] :as options}]
  (let [config
        (config/load-cljs-edn!)

        build-configs
        (->> builds
             (map (fn [build-id]
                    (let [build-config (get-in config [:builds build-id])]
                      (when-not build-config
                        (println (str "No config for build \"" (name build-id) "\" found.")))

                      build-config
                      )))
             (remove nil?)
             (into []))

        already-running?
        (some? @runtime/instance-ref)]

    (if (and (contains? #{:watch :cljs-repl} action)
             (empty? build-configs))
      (println "Build id required.")

      (do (when-not already-running?
            (start!))

          (case action
            :watch
            (let [before (api/active-builds)

                  stop-builds!
                  (fn []
                    (doseq [build builds
                            :when (not (contains? before build))]
                      (api/stop-worker build)))]

              (watch-builds config build-configs options)

              ;; start threads to wait for exit condition
              ;; not doing this in the main thread since reading from a socket blocks and can't be interrupted properly
              ;; FIXME: write proper remote API, doing this on top of the REPL is annoying
              (if socket-repl/*socket*
                (future
                  ;; when remote socket is active read until eof as watch does not expect any other input
                  ;; but should not exit if something is entered accidentally
                  (loop []
                    (let [x (try
                              (read *in* false ::eof)
                              (catch Exception e
                                (log/warn-ex e ::socket-read-ex)
                                ::eof))]
                      (if (not= x ::eof)
                        (recur)
                        (stop-builds!)
                        ))))
                (future
                  ;; wait till stdin is closed
                  ;; stop all builds so the other loop can initiate the shutdown
                  ;; done in separate loop since we cannot reliably do a non-blocking read from a blocking socket
                  ;; sort of hacking that as it is when looking at System/in directly
                  (loop []
                    (when (and (not (stdin-closed?))
                               @runtime/instance-ref)
                      (Thread/sleep 500)
                      (recur)))

                  (when @runtime/instance-ref
                    (stop-builds!))))

              ;; run until either the instance is removed
              ;; or all builds we started are stopped by other means
              (loop []
                (cond
                  (not (some? @runtime/instance-ref))
                  ::stopped

                  (not (some #(api/get-worker (:build-id %)) build-configs))
                  ::stopped

                  :else
                  (do (Thread/sleep 100)
                      (recur))))

              ;; if the builds were stopped by any other means the connected client should exit
              ;; since it is stuck in a blocking read thread above it we need to close the socket
              (when-let [s socket-repl/*socket*]
                (.close s)))

            :clj-repl
            (socket-repl/repl {})

            :cljs-repl
            (let [{:keys [supervisor] :as app}
                  @runtime/instance-ref

                  {:keys [build-id] :as build-config}
                  (first build-configs)]

              (if (false? (get-in build-config [:devtools :enabled]))
                (println (format "Build %s has :devtools {:enabled false}, can't connect to a REPL" build-id))
                (let [worker
                      (super/get-worker supervisor build-id)

                      started-by-us?
                      (nil? worker)

                      worker
                      (-> (or worker
                              (-> (super/start-worker supervisor build-config options)
                                  (worker/compile)))
                          ;; need to sync in case it is still compiling
                          (worker/sync!))]

                  (api/repl build-id)

                  (when started-by-us?
                    (super/stop-worker supervisor build-id)))))

            :node-repl
            (api/node-repl options)

            :browser-repl
            (api/browser-repl options)

            ;; makes this a noop if server is already running
            :server
            (if already-running?
              (println "server already running")
              (wait-for-stop!)))

          (when-not already-running?
            (stop!))))))

(comment
  (start!)

  (stop!))
