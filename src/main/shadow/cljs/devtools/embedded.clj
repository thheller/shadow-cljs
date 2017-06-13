(ns shadow.cljs.devtools.embedded
  (:refer-clojure :exclude (sync))
  (:require [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.repl-api :as repl-api]))

(defn start! []
  (server/start!))

(defn stop! []
  (server/stop!))

(defn start-worker [& args]
  (binding [repl-api/*app* server/app-instance]
    (apply repl-api/start-worker args)))

(defn stop-worker [& args]
  (binding [repl-api/*app* server/app-instance]
    (apply repl-api/stop-worker args)))
