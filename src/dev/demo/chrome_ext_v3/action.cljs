(ns demo.chrome-ext-v3.action)

(defn init []
  (js/console.log "yo!")
  (js/console.log (reduce + (range 10240)))
  )
