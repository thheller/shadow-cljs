(ns shadow.devtools.embedded
  (:require [shadow.server.runtime :as rt]
            [shadow.devtools.server.worker :as worker]
            [shadow.devtools.server.supervisor :as super]
            [shadow.devtools.server.config :as config]
            [shadow.devtools.server.util :as util]
            [shadow.devtools.server.common :as common]
            [clojure.core.async :as async]))

(defonce system-ref
  (volatile! nil))

(defn system []
  (let [x @system-ref]
    (when-not x
      (throw (ex-info "devtools not started" {})))
    x))

(def default-config
  {})

(defn app []
  (merge
    (common/app)
    {:supervisor
     {:depends-on [:fs-watch]
      :start super/start
      :stop super/stop}

     :out
     {:depends-on []
      :start util/stdout-dump
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
               (rt/init (app))
               (rt/start-all))]

       (vreset! system-ref system)
       ::started))))

(defn stop! []
  (when-some [system @system-ref]
    (rt/stop-all system)
    (vreset! system-ref nil))
  ::stopped)

(defn start-autobuild [build-id]
  (start!)

  (let [build-config
        (if (map? build-id)
          build-id
          (config/get-build! build-id))

        {:keys [supervisor out] :as app}
        (system)]

    (-> (super/start-worker supervisor build-id)
        (worker/watch out false)
        (worker/configure build-config)
        (worker/start-autobuild)))

  build-id)

