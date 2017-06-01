(ns shadow.cljs.devtools.standalone
  (:require [aleph.http :as aleph]
            [shadow.runtime.services :as rt]
            [shadow.cljs.devtools.server.web :as web]
            [shadow.cljs.devtools.server.explorer :as explorer]
            [shadow.cljs.devtools.server.config-watch :as config]
            [clojure.core.async :as async :refer (thread)]
            [shadow.cljs.devtools.server.common :as common]
            [aleph.netty :as netty]
            [shadow.cljs.devtools.server.supervisor :as super]))

(defonce runtime nil)

(def default-config
  {:http
   {:port 8200
    :host "localhost"}})

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
  [config system-ref]
  (-> (fn [ring-map]
        (let [app
              (-> (:app @system-ref)
                  (assoc :ring-request ring-map))]
          (web/root app)))
      ;; (file/wrap-file (io/file "public"))
      ;; (reload/wrap-reload {:dirs ["src/main"]})
      ))

(defmacro do-shutdown [& body]
  `(try
     ~@body
     (catch Throwable t#
       (println t# ~(str "shutdown failed: " (pr-str body))))))

(defn shutdown-system [{:keys [app http] :as system}]
  (do-shutdown (.close http))
  (do-shutdown (rt/stop-all app)))

(defn start-http [ring config]
  (aleph/start-server ring config))

(defn start-system
  ([]
   (start-system default-config))
  ([config]
   (let [runtime-ref
         (volatile! {})

         app
         (-> {::started (System/currentTimeMillis)
              :config config}
             (rt/init (app config))
             (rt/start-all))
         ]

     (vreset! runtime-ref {:app app})

     (let [ring
           (get-ring-handler config runtime-ref)

           hk
           (start-http ring (:http config))]

       (vswap! runtime-ref assoc :http hk))

     runtime-ref
     )))

(defn start! []
  (let [runtime-ref
        (start-system default-config)

        {:keys [host port]}
        (get-in @runtime-ref [:app :config :http])]

    (println (str "shadow-cljs - server running at http://" host ":" port))
    (alter-var-root #'runtime (fn [_] runtime-ref))
    ::started
    ))

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
