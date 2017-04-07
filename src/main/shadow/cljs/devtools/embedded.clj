(ns shadow.cljs.devtools.embedded
  (:require [shadow.server.runtime :as rt]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.common :as common]
            [clojure.core.async :as async :refer (go <!)]
            [shadow.cljs.devtools.api :as api]))

(def default-config
  {:verbose false})

(defonce system-ref
  (volatile! nil))

(defn system []
  (let [x @system-ref]
    (when-not x
      (throw (ex-info "devtools not started" {})))
    x))

(defn app [config]
  (merge
    (common/app config)
    {:supervisor
     {:depends-on [:system-bus]
      :start super/start
      :stop super/stop}

     :out
     {:depends-on [:config]
      :start (fn [{:keys [verbose]}]
               (util/stdout-dump verbose))
      :stop async/close!}
     }))

(defn start!
  ([]
   (start! default-config))
  ([config]
   (if @system-ref
     ::running
     (let [system
           (-> {::started (System/currentTimeMillis)
                :config config}
               (rt/init (app config))
               (rt/start-all))]

       (vreset! system-ref system)
       ::started))))

(defn stop! []
  (when-some [system @system-ref]
    (rt/stop-all system)
    (vreset! system-ref nil))
  ::stopped)

(defn start-worker
  ([build-id]
   (start-worker build-id {:autobuild true}))
  ([build-id {:keys [autobuild] :as opts}]
   (start!)
   (let [build-config
         (if (map? build-id)
           build-id
           (config/get-build! build-id))

         {:keys [supervisor out] :as app}
         (system)]

     (if-let [worker (super/get-worker supervisor build-id)]
       (when autobuild
         (worker/start-autobuild worker))

       (-> (super/start-worker supervisor build-id)
           (worker/watch out false)
           (worker/configure build-config)
           (cond->
             autobuild
             (worker/start-autobuild))
           (worker/sync!))
       ))
   ::started))

(defn repl [build-id]
  (let [{:keys [supervisor] :as sys} (system)]
    (let [worker (super/get-worker supervisor build-id)]
      (if-not worker
        ::worker-not-started
        (api/stdin-takeover! worker)
        ))))

(defn stop-worker [build-id]
  (when-let [{:keys [supervisor] :as sys} @system-ref]
    (super/stop-worker supervisor build-id))
  ::stopped)

(defn stop-autobuild [build-id]
  (let [{:keys [supervisor] :as sys} @system-ref]
    (if-not sys
      ::not-running
      (let [worker (super/get-worker supervisor build-id)]
        (if-not worker
          ::no-worker
          (do (worker/stop-autobuild worker)
              ::stopped))))))

;; FIXME: re-use running app instead of it starting a new one
(defn node-repl
  ([]
    (api/node-repl))
  ([opts]
    (api/node-repl opts)))


(comment
  (start!)

  (start-worker :website)

  (stop-worker :website)

  (stop!))