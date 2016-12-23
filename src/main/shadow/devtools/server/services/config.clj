(ns shadow.devtools.server.services.config
  (:require [clojure.core.async :as async]
            [clojure.java.io :as io]))

(defn- service? [x]
  (and (map? x)
       (::service x)))

(defn start []
  (let [config-update
        (async/chan)

        config-update-pub
        (async/pub config-update :id)

        control
        (async/chan)

        state-ref
        (volatile! nil)]

    {::service true
     :state-ref state-ref
     :control control
     :config-update config-update
     :config-update-pub config-update-pub}))

(defn stop [svc]
  {:pre [(service? svc)]})
