(ns shadow.cljs.devtools.server.supervisor
  (:require [shadow.cljs.devtools.server.worker :as worker]
            [clojure.core.async :as async :refer (go <! alt!)]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [clojure.tools.logging :as log]))

(defn get-worker
  [{:keys [workers-ref] :as svc} id]
  (get @workers-ref id))

(defn active-builds [{:keys [workers-ref] :as super}]
  (->> @workers-ref
       (keys)
       (into #{})))

(defn get-status [{:keys [workers-ref] :as svc}]
  (reduce-kv
    (fn [status worker-id worker-proc]
      (assoc status worker-id (worker/get-status worker-proc)))
    {}
    @workers-ref))

(defn start-worker
  [{:keys [system-bus state-ref workers-ref executor cache-root http classpath npm] :as svc} {:keys [build-id] :as build-config}]
  {:pre [(keyword? build-id)]}
  (when (get @workers-ref build-id)
    (throw (ex-info "already started" {:build-id build-id})))

  (let [{:keys [proc-stop] :as proc}
        (worker/start system-bus executor cache-root http classpath npm build-config)]

    (vswap! workers-ref assoc build-id proc)

    (go (<! proc-stop)
        (vswap! workers-ref dissoc build-id))

    proc
    ))

(defn stop-worker
  [{:keys [workers-ref] :as svc} id]
  {:pre [(keyword? id)]}
  (when-some [proc (get @workers-ref id)]
    (worker/stop proc)))

(defn start [system-bus executor cache-root http classpath npm]
  {:system-bus system-bus
   :executor executor
   :cache-root cache-root
   :http http
   :classpath classpath
   :npm npm
   :workers-ref (volatile! {})})

(defn stop [{:keys [workers-ref] :as svc}]
  (doseq [[id proc] @workers-ref]
    (worker/stop proc))

  ::stop)
