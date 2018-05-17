(ns demo.chrome-bg)

(js/chrome.runtime.onInstalled.addListener
  (fn []
    (js/console.log "Installed!")
    ))