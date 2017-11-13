(ns demo.rel-require
  (:require ["./foo" :as foo]))

(defn main []
  (js/console.log "foo" foo))