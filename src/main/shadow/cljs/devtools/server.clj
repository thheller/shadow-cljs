(ns shadow.cljs.devtools.server
  (:require [aleph.http :as aleph]
            [clojure.core.async :as async :refer (thread go <!)]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [aleph.netty :as netty]
            [ring.middleware.file :as ring-file]
            [ring.middleware.params :as ring-params]
            [shadow.repl :as r]
            [shadow.http.router :as http]
            [shadow.runtime.services :as rt]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.server.web :as web]
            [shadow.cljs.devtools.server.config-watch :as config-watch]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.common :as common]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.socket-repl :as socket-repl]
            [shadow.cljs.devtools.server.runtime :as runtime]
            [shadow.cljs.devtools.server.nrepl :as nrepl]
            [shadow.cljs.devtools.server.dev-http :as dev-http]))

(defn app [config]
  (merge
    (common/app config)
    {:dev-http
     {:depends-on [:executor :out]
      :start dev-http/start
      :stop dev-http/stop}
     :out
     {:depends-on []
      :start #(util/stdout-dump (:verbose config))
      :stop async/close!}
     }))

(defn get-ring-handler
  [{:keys [dev-mode] :as config} app-promise]
  (-> (fn [ring-map]
        (let [app (deref app-promise 1000 ::timeout)]
          (if (= app ::timeout)
            {:status 501
             :body "App not ready!"}
            (-> app
                (assoc :ring-request ring-map)
                (http/prepare)
                (web/root)))))
      (cond->
        dev-mode
        (ring-file/wrap-file (io/file "target/shadow-cljs/ui/output")))
      ;; (reload/wrap-reload {:dirs ["src/main"]})
      (ring-params/wrap-params)
      ))

(defmacro do-shutdown [& body]
  `(try
     ~@body
     (catch Throwable t#
       (println t# ~(str "shutdown failed: " (pr-str body))))))

(defn shutdown-system [{:keys [http port-files socket-repl cli-repl nrepl] :as app}]
  (println "shutting down ...")
  (do-shutdown
    (doseq [port-file (vals port-files)]
      (.delete port-file)))
  (do-shutdown (rt/stop-all app))
  (do-shutdown (socket-repl/stop socket-repl))
  (do-shutdown (socket-repl/stop cli-repl))
  (when nrepl
    (do-shutdown (nrepl/stop nrepl)))
  (let [netty (:server http)]
    (do-shutdown
      (.close netty)
      (netty/wait-for-close netty)))

  (println "shutdown complete."))

(defn make-port-files [cache-root ports]
  (io/make-parents (io/file cache-root "foo.txt"))

  (reduce-kv
    (fn [result key port]
      (assert (keyword? key))
      (assert (pos-int? port))
      (let [port-file
            (doto (io/file cache-root (str (name key) ".port"))
              (.deleteOnExit))]
        (spit port-file (str port))
        (assoc result key port-file)))
    {}
    ports))

(defn start-system
  [app {:keys [cache-root] :as config}]
  (let [app-promise
        (promise)

        ring
        (get-ring-handler config app-promise)

        http-config
        (merge {:port 0 :host "localhost"} (:http config))

        http
        (aleph/start-server ring http-config)

        http-port
        (netty/port http)

        socket-repl-config
        (:socket-repl config)

        socket-repl
        (socket-repl/start socket-repl-config app-promise)

        cli-repl-config
        {:port 0 ;; random port, not for humans
         :prompt false
         :print false}

        ;; remote entry point for the CLI tool that just sends one command
        ;; and waits for the socket to close, not using the normal socket REPL
        ;; because that prints/prompts for humans
        cli-repl
        (socket-repl/start cli-repl-config app-promise)

        nrepl
        (nrepl/start (:nrepl config))

        ;; FIXME: should refuse to start if a pid already exists
        port-files
        (make-port-files cache-root
          {:nrepl (:port nrepl)
           :socket-repl (:port socket-repl)
           :cli-repl (:port cli-repl)
           :http http-port})

        app
        (-> {::started (System/currentTimeMillis)
             :config config
             :http {:port http-port
                    :host (:host http-config)
                    :server http}
             :port-files port-files
             :nrepl nrepl
             :socket-repl socket-repl
             :cli-repl cli-repl}
            (rt/init app)
            (rt/start-all))]

    (deliver app-promise app)

    ;; autostart is not cool?
    #_(future
        ;; OCD because I want to print the shadow-cljs info of start!
        ;; before any build output
        (Thread/sleep 100)
        (when-let [{:keys [autostart] :as srv-config} (:server config)]
          (doseq [build-id autostart]
            (super/start-worker (:supervisor app) build-id)
            )))

    app
    ))

(defn load-config []
  (-> (config/load-cljs-edn)
      ;; system config doesn't need build infos
      (dissoc :builds)))

(defn start!
  ([]
   (let [config (load-config)]
     (start! config (app config))))
  ([sys-config]
   (start! sys-config (app sys-config)))
  ([sys-config app]
   (let [{:keys [http socket-repl nrepl config] :as app}
         (start-system app sys-config)]

     (when (get-in config [:http :port])
       (println (str "shadow-cljs - server running at http://" (:host http) ":" (:port http))))

     (when (get-in config [:socket-repl :port])
       (println (str "shadow-cljs - socket repl running at " (:host socket-repl) ":" (:port socket-repl))))

     (when (get-in config [:nrepl :port])
       (println (str "shadow-cljs - nrepl running at "
                     (-> (:server-socket nrepl) (.getInetAddress))
                     ":" (:port nrepl))))

     (runtime/set-instance! app)
     ::started
     )))

(defn stop!
  ([]
   (when @runtime/instance-ref
     (shutdown-system @runtime/instance-ref)
     (runtime/reset-instance!)
     )))

(defn -main [& args]
  (start!)
  (-> @runtime/instance-ref
      (get-in [:http :server])
      (netty/wait-for-close))
  (shutdown-agents))

(defn wait-for-http []
  (-> @runtime/instance-ref
      (get-in [:http :server])
      (netty/wait-for-close)))

(defn wait-for-eof! []
  (loop []
    (let [x (try
              (read *in* false ::eof)
              (catch Exception e
                ::eof))]
      (when (not= x ::eof)
        (recur)))))

(defn watch-builds [build-ids options]
  (let [{:keys [supervisor] :as app}
        @runtime/instance-ref

        {:keys [builds] :as config}
        (config/load-cljs-edn!)

        out-chan
        (-> (async/sliding-buffer 100)
            (async/chan))

        verbose
        (or (:verbose options)
            (:verbose config))]

    (doseq [build-id build-ids]
      (let [{:keys [supervisor] :as app}
            @runtime/instance-ref

            build-config
            (get builds build-id)]

        (println "shadow-cljs - watching build" build-id)
        (-> (api/get-or-start-worker build-config options)
            (worker/watch out-chan false)
            (worker/start-autobuild))))

    (go (loop []
          (when-some [msg (<! out-chan)]
            (try
              (util/print-worker-out msg verbose)
              (catch Exception e
                (prn [:print-worker-out-error e])))
            (recur)
            )))
    ))

(defn from-cli [action builds {:keys [verbose] :as options}]
  (if (and (contains? #{:watch :cljs-repl} action)
           (empty? builds))
    (println "Build id required.")

    (let [already-running?
          (some? @runtime/instance-ref)]

      (when-not already-running?
        (start!))

      (case action
        :watch
        (do (let [before (api/active-builds)]
              (watch-builds builds options)

              (wait-for-eof!)

              ;; stop only builds that weren't running before
              (doseq [build builds
                      :when (not (contains? before build))]
                (api/stop-worker build))))

        :clj-repl
        (r/enter-root {}
          (socket-repl/repl {}))

        :cljs-repl
        (let [{:keys [supervisor] :as app}
              @runtime/instance-ref

              build-id
              (first builds)

              build-config
              (config/get-build! build-id)]

          (if (false? (get-in build-config [:devtools :enabled]))
            (println (format "Build %s has :devtools {:enabled false}, can't connect to a REPL" build-id))
            (let [worker
                  (super/get-worker supervisor build-id)

                  started-by-us?
                  (nil? worker)

                  worker
                  (-> (or worker
                          (-> (super/start-worker supervisor build-config)
                              (worker/compile)))
                      ;; need to sync in case it is still compiling
                      (worker/sync!))]

              (api/repl build-id)

              (when started-by-us?
                (super/stop-worker supervisor build-id)))))

        :node-repl
        (let [{:keys [supervisor] :as app} @runtime/instance-ref

              worker
              (super/get-worker supervisor :node-repl)]
          ;; FIXME: connect to if already running or launch another one?
          (if-not worker
            (do (println "shadow-cljs - starting node-repl")
                (api/node-repl options))

            (do (println "shadow-cljs - connecting to running node-repl")
                (api/repl :node-repl))
            ))

        ;; makes this a noop if server is already running
        :server
        (if already-running?
          (println "server already running")
          (wait-for-eof!)))

      (when-not already-running?
        (stop!)
        (shutdown-agents)))))

(comment
  (start!)

  (stop!))
