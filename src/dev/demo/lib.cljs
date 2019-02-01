(ns demo.lib
  (:require ["which" :as w]))

(js/console.log "demo.lib")

(defn hello []
  (w/sync "java" #js {:nothrow true}))

