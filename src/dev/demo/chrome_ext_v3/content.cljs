(ns demo.chrome-ext-v3.content)

(js/console.warn "ALL YOUR PAGE ARE BELONG TO US!!!")

(defn init []
  (js/console.log "▶❤◀"))

(defn ^:dev/after-load call-me-maybe []
  (js/console.warn "actually called!"))