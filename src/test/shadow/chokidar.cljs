(ns shadow.chokidar
  (:require ["chokidar" :refer (watch)]))


(def watcher
  (watch
    #js ["src/dev" "src/main" "src/test"]
    #js {}))

(defn watch-callback [event path]
  (js/console.log event path))

(defn main [& args]
  (.on watcher "all" watch-callback))