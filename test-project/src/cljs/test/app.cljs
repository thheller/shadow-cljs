(ns test.app)

(def x {:hello})

(defn ^:export start []
  (js/console.log "hello world, try the repl"))
