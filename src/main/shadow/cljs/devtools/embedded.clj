(ns shadow.cljs.devtools.embedded
  (:refer-clojure :exclude (sync))
  (:require [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.api :as api]))

(defn start! []
  (server/start!))

(defn stop! []
  (server/stop!))

(defn start-worker [& args]
  (apply api/watch args))

(defn stop-worker [& args]
  (apply api/stop-worker args))
