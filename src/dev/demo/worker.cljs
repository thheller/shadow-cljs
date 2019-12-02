(ns demo.worker)

;; this ns in only executed by the worker

(defn ^:dev/after-load start []
  (js/console.log "worker reload"))

(defn init []
  (js/console.log "worker started")
  (js/postMessage (pr-str (assoc {:x 1} :y 2))))


