(ns demo.chrome-ext-v3.content)

(defn init []
  (js/console.log "▶❤◀")
  (js/console.log (js/chrome.runtime.getURL "")))

(defn ^:dev/after-load call-me-maybe []
  (js/console.warn "actually called!"))