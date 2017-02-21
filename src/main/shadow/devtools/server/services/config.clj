(ns shadow.devtools.server.services.config
  (:require [shadow.devtools.server.config :as config]))

(defn- service? [x]
  (and (map? x)
       (::service x)))

(defn get-configured-builds [svc]
  (:builds svc))

(defn start []
  {::service true
   :builds (config/load-cljs-edn!)})

(defn stop [svc])

