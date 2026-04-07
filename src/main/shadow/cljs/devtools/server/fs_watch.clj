(ns shadow.cljs.devtools.server.fs-watch
  (:require [shadow.fswatch :as delegate]))

(defn start [config directories file-exts publish-fn]
  (delegate/start config directories file-exts publish-fn))

(defn stop [svc]
  (delegate/stop svc))