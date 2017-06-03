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
            [shadow.lang.json-rpc.socket-server :as lang-server]
            [shadow.cljs.devtools.server.remote-api]))

(defonce runtime nil)

(defn app [config]
  (merge
    (common/app config)
    {:supervisor
     {:depends-on [:system-bus :executor]
      :start super/start
      :stop super/stop}

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
  (do-shutdown (lang-server/stop lang-server))
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

          lang-server
          (lang-server/start app)

          pid-file
          (io/file cache-root "remote.pid")]

      (spit pid-file (str (netty/port http)))

      (.deleteOnExit pid-file)

      (vswap! runtime-ref assoc :http http :lang-server lang-server :pid-file pid-file))

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

(comment
  (start!)

  (stop!))
