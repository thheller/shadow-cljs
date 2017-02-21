(ns shadow.devtools.server.standalone
  (:require [aleph.http :as aleph]
            [shadow.server.runtime :as rt]
            [shadow.devtools.server.web :as web]
            [shadow.devtools.server.services.explorer :as explorer]
            [shadow.devtools.server.services.config :as config]
            [clojure.core.async :as async :refer (thread)]
            [clojure.java.io :as io]
            [ring.middleware.file :as file]

            [shadow.devtools.server.common :as common]
            ))

(defonce runtime nil)

(def default-config
  {:http
   {:port 8200
    :host "localhost"}})

(defn app []
  (merge
    (common/app)
    {:build-config
     {:depends-on []
      :start config/start
      :stop config/stop}

     :explorer
     {:depends-on [:fs-watch]
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
      (file/wrap-file (io/file "public"))
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
             (rt/init (app))
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

(defn -main []
  (println "Starting DEVTOOLS")
  (let [runtime-ref (start-system default-config)]
    (println "DEVTOOLS ready")
    (alter-var-root #'runtime (fn [_] runtime-ref))
    nil
    ))

