(ns shadow.cljs.devtools.server
  (:require [aleph.http :as aleph]
            [clojure.core.async :as async :refer (thread)]
            [clojure.java.io :as io]
            [aleph.netty :as netty]
            [ring.middleware.file :as ring-file]
            [shadow.runtime.services :as rt]
            [shadow.cljs.devtools.server.web :as web]
            [shadow.cljs.devtools.server.explorer :as explorer]
            [shadow.cljs.devtools.server.config-watch :as config-watch]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.common :as common]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.socket-repl :as socket-repl]
            [shadow.cljs.devtools.server.runtime :as runtime]
            [shadow.repl :as repl]
            [shadow.http.router :as http]))

(defn app [config]
  (merge
    (common/app config)
    {:out
     {:depends-on []
      :start #(util/stdout-dump (:verbose config))
      :stop async/close!}

     :explorer
     {:depends-on [:system-bus]
      :start explorer/start
      :stop explorer/stop}
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
                (http/do-ring web/root)))))
      (cond->
        dev-mode
        (ring-file/wrap-file (io/file "target/shadow-cljs/ui/output")))
      ;; (reload/wrap-reload {:dirs ["src/main"]})
      ))

(defmacro do-shutdown [& body]
  `(try
     ~@body
     (catch Throwable t#
       (println t# ~(str "shutdown failed: " (pr-str body))))))

(defn shutdown-system [{:keys [http pid-file socket-repl] :as app}]
  (println "shutting down ...")
  (do-shutdown (.delete pid-file))
  (do-shutdown (rt/stop-all app))
  (do-shutdown (socket-repl/stop socket-repl))
  (let [netty (:server http)]
    (do-shutdown
      (.close netty)
      (netty/wait-for-close netty)))
  (println "shutdown complete."))

(defn start-system
  [app {:keys [cache-root] :as config}]
  (let [app-promise
        (promise)

        ring
        (get-ring-handler config app-promise)

        http
        (aleph/start-server ring (:http config))

        http-port
        (netty/port http)

        socket-repl
        (socket-repl/start (:repl config) app-promise)

        pid-file
        (doto (io/file cache-root "remote.pid")
          (.deleteOnExit))

        app
        (-> {::started (System/currentTimeMillis)
             :config config
             :pid-file pid-file
             :http {:port http-port
                    :host "localhost" ;; FIXME: take from config or netty instance
                    :server http}
             :socket-repl socket-repl}
            (rt/init app)
            (rt/start-all))]

    (deliver app-promise app)

    ;; FIXME: should refuse to start if a pid already exists
    (spit pid-file (pr-str {:http http-port
                            :socket-repl (:port socket-repl)}))

    (future
      ;; OCD because I want to print the shadow-cljs info of start!
      ;; before and build output
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
   (let [{:keys [http socket-repl] :as app}
         (start-system app sys-config)]

     (println (str "shadow-cljs - server running at http://" (:host http) ":" (:port http)))
     (println (str "shadow-cljs - socket repl running at tcp://" (:host socket-repl) ":" (:port socket-repl)))

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

(defn from-cli [options]
  (if @runtime/instance-ref
    (println "TBD: you just connected to a running server, can't start another one")

    (do (start!)
        (-> @runtime/instance-ref
            (get-in [:http :server])
            (netty/wait-for-close))
        (shutdown-agents))))

(comment
  (start!)

  (stop!))
