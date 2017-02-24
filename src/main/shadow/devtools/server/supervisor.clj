(ns shadow.devtools.server.supervisor
  (:require [shadow.devtools.server.worker :as worker]
            [clojure.core.async :as async :refer (go <!)]))

(defn get-worker
  [{:keys [workers-ref] :as svc} id]
  (get @workers-ref id))

(defn start-worker
  [{:keys [fs-watch workers-ref] :as svc} id]
  (when (get @workers-ref id)
    (throw (ex-info "already started" {:id id})))

  (let [{:keys [proc-stop] :as proc}
        (worker/start fs-watch)]

    (go (<! proc-stop)
        (vswap! workers-ref dissoc id))

    (vswap! workers-ref assoc id proc)
    proc
    ))

(defn stop-worker
  [{:keys [workers-ref] :as svc} id]
  (when-some [proc (get @workers-ref id)]
    (worker/stop proc)))

(defn start [fs-watch]
  {:fs-watch fs-watch
   :workers-ref (volatile! {})})

(defn stop [{:keys [workers-ref] :as svc}]
  (doseq [[id proc] @workers-ref]
    (worker/stop proc))

  ::stop)
