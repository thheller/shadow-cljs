(ns shadow.cljs.devtools.server.supervisor
  (:require [shadow.cljs.devtools.server.worker :as worker]
            [clojure.core.async :as async :refer (go <! alt!)]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [clojure.tools.logging :as log]))

(defn get-worker
  [{:keys [workers-ref] :as svc} id]
  (get @workers-ref id))

(defn active-builds [{:keys [workers-ref] :as super}]
  (-> @workers-ref
      (keys)
      (into #{})))

(defn get-status [{:keys [workers-ref] :as svc}]
  (reduce-kv
    (fn [status worker-id worker-proc]
      (assoc status worker-id (worker/get-status worker-proc)))
    {}
    @workers-ref))

(defn start-worker
  [{:keys [system-bus state-ref workers-ref executor http] :as svc} {:keys [id] :as build-config}]
  {:pre [(keyword? id)]}
  (when (get @workers-ref id)
    (throw (ex-info "already started" {:id id})))

  (let [{:keys [proc-stop] :as proc}
        (worker/start system-bus executor http build-config)]

    (vswap! workers-ref assoc id proc)

    (go (<! proc-stop)
        (vswap! workers-ref dissoc id))

    proc
    ))

(defn stop-worker
  [{:keys [workers-ref] :as svc} id]
  {:pre [(keyword? id)]}
  (when-some [proc (get @workers-ref id)]
    (worker/stop proc)))

(defn start [system-bus executor http]
  {:system-bus system-bus
   :executor executor
   :http http
   :workers-ref (volatile! {})})

(defn stop [{:keys [workers-ref] :as svc}]
  (doseq [[id proc] @workers-ref]
    (worker/stop proc))

  ::stop)
