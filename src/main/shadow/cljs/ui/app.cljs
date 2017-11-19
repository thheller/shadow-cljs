(ns shadow.cljs.ui.app
  (:require [shadow.api :refer (ns-ready)]))

(defn start []
  (js/console.log "start"))

(defn stop []
  (js/console.log "stop"))

(defn ^:export init []
  (js/console.log "init")
  (start))

(ns-ready)