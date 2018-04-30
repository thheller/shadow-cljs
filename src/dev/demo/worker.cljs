(ns demo.worker
  (:require ["react" :as r]))

;; this ns in only executed by the worker

(js/console.log "worker started" (r/createElement "h1" nil "hello world"))
(js/postMessage (pr-str (assoc {:x 1} :y 2)))
