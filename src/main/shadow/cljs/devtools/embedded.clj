(ns shadow.cljs.devtools.embedded
  (:refer-clojure :exclude (sync))
  (:require [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.worker :as worker]))

(defn start! []
  (server/start!))

(defn stop! []
  (server/stop!))

(defn start-worker [id]
  (server/start-worker id))

(defn stop-worker [build-id]
  (when-let [{:keys [supervisor] :as sys} server/app-instance]
    (super/stop-worker supervisor build-id))
  ::stopped)
