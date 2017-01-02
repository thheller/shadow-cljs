(ns shadow.devtools.frontend.app
  (:require [shadow.api :refer (ns-ready)]
            ))

(defn start []
  (js/console.log "app-start"))

(defn stop []
  (js/console.log "app-stop"))

(ns-ready)
