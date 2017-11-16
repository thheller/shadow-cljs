(ns demo.closure
  (:require ["./es6" :as x :refer (foo)]))

(js/console.log "foo" (foo "foo"))
(js/console.log "bar" (foo "bar"))
