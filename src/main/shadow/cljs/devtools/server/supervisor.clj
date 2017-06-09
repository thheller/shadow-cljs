(ns shadow.cljs.devtools.server.supervisor
  (:require [shadow.cljs.devtools.server.worker :as worker]
            [clojure.core.async :as async :refer (go <!)]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]))

(defn get-worker
  [{:keys [workers-ref] :as svc} id]
  (get @workers-ref id))

(defn start-worker
  [{:keys [system-bus workers-ref executor] :as svc} id]
  (when (get @workers-ref id)
    (throw (ex-info "already started" {:id id})))

  (let [{:keys [proc-stop] :as proc}
        (worker/start system-bus executor)]

    (go (<! proc-stop)
        (vswap! workers-ref dissoc id))

    (vswap! workers-ref assoc id proc)
    proc
    ))

(defn stop-worker
  [{:keys [workers-ref] :as svc} id]
  (when-some [proc (get @workers-ref id)]
    (worker/stop proc)))

(defn start [system-bus executor]
  {:system-bus system-bus
   :executor executor
   :workers-ref (volatile! {})})

(defn stop [{:keys [workers-ref] :as svc}]
  (doseq [[id proc] @workers-ref]
    (worker/stop proc))

  ::stop)
