(ns shadow.cljs.devtools.server.supervisor
  (:require [shadow.cljs.devtools.server.worker :as worker]
            [clojure.core.async :as async :refer (go <! alt!)]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.model :as m]
            [shadow.jvm-log :as log]))

(defn get-worker
  [{:keys [workers-ref] :as svc} id]
  (get @workers-ref id))

(defn active-builds [{:keys [workers-ref] :as super}]
  (->> @workers-ref
       (keys)
       (into #{})))

(defonce super-lock (Object.))

(defn start-worker
  [{:keys [system-bus
           workers-ref
           executor
           relay
           clj-runtime
           clj-obj-support
           cache-root
           http
           classpath
           npm
           babel
           config]
    :as svc}
   {:keys [build-id] :as build-config}
   cli-opts]
  {:pre [(keyword? build-id)]}
  ;; locking to prevent 2 threads starting the same build at the same time (unlikely but still)
  (locking super-lock
    (when (get @workers-ref build-id)
      (throw (ex-info "already started" {:build-id build-id})))

    (let [{:keys [proc-stop] :as proc}
          (worker/start
            {:config config
             :system-bus system-bus
             :executor executor
             :relay relay
             :clj-runtime clj-runtime
             :clj-obj-support clj-obj-support
             :cache-root cache-root
             :http http
             :classpath classpath
             :npm npm
             :babel babel
             :build-config build-config
             :cli-opts cli-opts})]

      (sys-bus/publish! system-bus ::m/supervisor {::m/worker-op :worker-start
                                                   ::m/build-id build-id})

      (swap! workers-ref assoc build-id proc)

      (go (<! proc-stop)
          (sys-bus/publish! system-bus ::m/supervisor {::m/worker-op :worker-stop
                                                       ::m/build-id build-id})
          (swap! workers-ref dissoc build-id))

      proc
      )))

(defn stop-worker
  [{:keys [workers-ref] :as svc} id]
  {:pre [(keyword? id)]}
  (when-some [proc (get @workers-ref id)]
    (worker/stop proc)))

;; FIXME: too many args, use a map
(defn start [config system-bus executor relay clj-runtime clj-obj-support cache-root http classpath npm babel]
  {:system-bus system-bus
   :config config
   :executor executor
   :relay relay
   :clj-runtime clj-runtime
   :clj-obj-support clj-obj-support
   :cache-root cache-root
   :http http
   :classpath classpath
   :npm npm
   :babel babel
   :workers-ref (atom {})})

(defn stop [{:keys [workers-ref] :as svc}]
  (doseq [[id proc] @workers-ref]
    (worker/stop proc))

  ::stop)
