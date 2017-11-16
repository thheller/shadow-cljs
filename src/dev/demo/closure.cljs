(ns demo.closure
  (:require ["./es6" :as x :refer (foo) :default defaultExport]))

(js/console.log "foo" (foo "foo"))
(js/console.log "bar" (foo "bar"))
(js/console.log defaultExport)
