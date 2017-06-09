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
            [shadow.cljs.devtools.remote.tcp-server :as remote-server]
            [shadow.cljs.devtools.remote.api :as remote-api]
            [shadow.cljs.devtools.server.worker :as worker]))

(defonce runtime nil)

(defn app [config]
  (merge
    (common/app config)
    {:supervisor
     {:depends-on [:system-bus :executor]
      :start super/start
      :stop super/stop}

     :remote-api
     {:depends-on [:system-bus]
      :start remote-api/start
      :stop remote-api/stop}

     :remote-server
     {:depends-on [:config :remote-api]
      :start (fn [{:keys [remote]} remote-api]
               (remote-server/start remote remote-api))
      :stop remote-server/stop}

     :explorer
     {:depends-on [:system-bus]
      :start explorer/start
      :stop explorer/stop}
     }))

(defn get-ring-handler
  [{:keys [dev-mode] :as config} system-ref]
  (-> (fn [ring-map]
        (let [app
              (-> (:app @system-ref)
                  (assoc :ring-request ring-map))]
          (web/root app)))
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

(defn shutdown-system [{:keys [app http lang-server pid-file] :as system}]
  (println "shutting down ...")
  (do-shutdown (rt/stop-all app))
  (do-shutdown
    (.close http)
    (netty/wait-for-close http))

  (do-shutdown (.delete pid-file))
  (println "shutdown complete."))

(defn start-http [ring config]
  (aleph/start-server ring config))

(defn start-system [{:keys [cache-root] :as config}]
  (let [runtime-ref
        (volatile! {})

        app
        (-> {::started (System/currentTimeMillis)
             :config config}
            (rt/init (app config))
            (rt/start-all))]

    (vreset! runtime-ref {:app app})

    (let [ring
          (get-ring-handler config runtime-ref)

          http
          (start-http ring (:http config))

          pid-file
          (io/file cache-root "remote.pid")]

      (spit pid-file (str (netty/port http)))

      (.deleteOnExit pid-file)

      (vswap! runtime-ref assoc :http http :pid-file pid-file))

    runtime-ref
    ))

(defn start!
  ([] (start! (config/load-cljs-edn)))
  ([config]
   (let [{:keys [cache-root] :as config} ;; builds are accessed elsewhere, this is only system config
         (dissoc config :builds)

         runtime-ref
         (start-system config)

         {:keys [host port]}
         (get-in @runtime-ref [:app :config :http])]

     (println (str "shadow-cljs - server running at http://" host ":" port))
     (alter-var-root #'runtime (fn [_] runtime-ref))
     ::started
     )))

(defn stop! []
  (when runtime
    (shutdown-system @runtime))
  ::stopped)

(defn -main [& args]
  (start!)
  (netty/wait-for-close (:http @runtime))
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

         {:keys [supervisor] :as app}
         (:app @runtime)]

     (if-let [worker (super/get-worker supervisor build-id)]
       (when autobuild
         (worker/start-autobuild worker))

       (-> (super/start-worker supervisor build-id)
           (worker/configure build-config)
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
