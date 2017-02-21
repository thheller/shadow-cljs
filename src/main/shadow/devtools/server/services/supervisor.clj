(ns shadow.devtools.server.services.supervisor
  (:require [shadow.devtools.server.services.build :as build]
            [clojure.core.async :as async :refer (go <!)]))

(defn start-build
  [{:keys [fs-watch builds-ref] :as svc} id]
  (when (get @builds-ref id)
    (throw (ex-info "already started" {:id id})))

  (let [{:keys [proc-stop] :as proc}
        (build/start fs-watch)]

    (go (<! proc-stop)
        (vswap! builds-ref dissoc id))

    (vswap! builds-ref assoc id proc)
    proc
    ))

(defn stop-build
  [{:keys [builds-ref] :as svc} id]
  (when-some [proc (get @builds-ref id)]
    (build/stop proc)))

(defn start [fs-watch]
  {:fs-watch fs-watch
   :builds-ref (volatile! {})})

(defn stop [{:keys [builds-ref] :as svc}]
  (doseq [[id proc] @builds-ref]
    (build/stop proc))

  ::stop)
