(ns shadow.lang.main
  (:require [shadow.server.runtime :as rt]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.config :as config]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.common :as common]
            [shadow.lang.json-rpc.socket-server :as lang-server]
            [clojure.core.async :as async :refer (go <!)]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.server.explorer :as explorer]
            [shadow.lang.classpath :as classpath]))

(def default-config
  {:verbose true
   :watch false})

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

     :classpath
     {:depends-on []
      :start classpath/start
      :stop classpath/stop}

     :cljs-explorer
     {:depends-on [:system-bus]
      :start explorer/start
      :stop explorer/stop}

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
               (rt/start-all))

           lang-server
           (lang-server/start system)]

       (vreset! system-ref (assoc system ::lang-server lang-server))
       ::started))))

(defn stop! []
  (when-some [system @system-ref]
    (lang-server/stop (::lang-server system))
    (rt/stop-all system)
    (vreset! system-ref nil)

    ::stopped))

(defn -main []
  (start!))

(comment
  (start!)

  (stop!))
