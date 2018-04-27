(ns demo.worker)

;; this ns in only executed by the worker

(js/console.log "worker started")
(js/postMessage (pr-str (assoc {:x 1} :y 2)))
