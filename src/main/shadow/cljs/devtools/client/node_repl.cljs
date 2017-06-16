(ns shadow.cljs.devtools.client.node-repl
  (:require [shadow.cljs.devtools.client.node :as node]
            [shadow.cljs.devtools.client.console])) ;; for --inspect, doesn't hurt otherwise

;; FIXME: anything useful we can run for a standalone CLJS repl?
;; repl related things will already have executed and started to connect (async)
(defn main [])