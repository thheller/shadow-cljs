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
            [shadow.cljs.devtools.server.util :as util]))

(defonce app-instance nil)

(defn app [config]
  (merge
    (common/app config)
    {

     :out
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
                (web/root)))))
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

(defn shutdown-system [{:keys [http pid-file] :as app}]
  (println "shutting down ...")
  (do-shutdown (.delete pid-file))
  (do-shutdown (rt/stop-all app))
  (let [netty (:server http)]
    (do-shutdown
      (.close netty)
      (netty/wait-for-close netty)))
  (println "shutdown complete."))

(defn start-system [{:keys [cache-root] :as config}]
  (let [runtime-ref
        (volatile! nil)

        app-promise
        (promise)

        ring
        (get-ring-handler config app-promise)

        http
        (aleph/start-server ring (:http config))

        http-port
        (netty/port http)

        pid-file
        (doto (io/file cache-root "remote.pid")
          (.deleteOnExit))

        app
        (-> {::started (System/currentTimeMillis)
             :config config
             :pid-file pid-file
             :http {:port (netty/port http)
                    :host "localhost" ;; FIXME: take from config or netty instance
                    :server http}}
            (rt/init (app config))
            (rt/start-all))]

    (deliver app-promise app)

    (spit pid-file (str (netty/port http)))

    app
    ))

(defn start!
  ([] (start! (config/load-cljs-edn)))
  ([config]
   (let [{:keys [cache-root] :as config} ;; builds are accessed elsewhere, this is only system config
         (dissoc config :builds)

         {:keys [http] :as app}
         (start-system config)

         {:keys [host port]}
         http]

     (println (str "shadow-cljs - server running at http://" host ":" port))
     (alter-var-root #'app-instance (fn [_] app))
     ::started
     )))

(defn stop! []
  (when app-instance
    (shutdown-system app-instance))
  ::stopped)

(defn -main [& args]
  (start!)
  (netty/wait-for-close (get-in app-instance [:http :server]))
  (shutdown-agents))

;; temp
(defn start-worker
  ([build-id]
   (start-worker build-id {:autobuild true}))
  ([build-id {:keys [autobuild] :as opts}]
   (let [build-config
         (if (map? build-id)
           build-id
           (config/get-build! build-id))

         {:keys [out supervisor] :as app}
         app-instance]

     (if-let [worker (super/get-worker supervisor build-id)]
       (when autobuild
         (worker/start-autobuild worker))

       (-> (super/start-worker supervisor build-config)
           (worker/watch out false)
           (cond->
             autobuild
             (worker/start-autobuild))
           ;; FIXME: sync to ensure the build finished before start-worker returns?
           ;; (worker/sync!)
           )))
   ::started))

(comment
  (start!)

  (stop!))
