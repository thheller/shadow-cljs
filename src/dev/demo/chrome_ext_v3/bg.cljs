(ns demo.chrome-ext-v3.bg)

(defn init []
  (js/console.log "chrome-bg")


  (js/console.log "▶❤◀")

  (js/chrome.runtime.onInstalled.addListener
    (fn []
      (js/console.log "Installed!")
      (js/console.log "Hello Chromex.")
      )))