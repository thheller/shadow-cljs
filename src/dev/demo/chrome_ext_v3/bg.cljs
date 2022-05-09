(ns demo.chrome-ext-v3.bg)

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn init []
  (js/console.log "chrome-bg")
  (js/console.log "▶❤◀")
  (js/console.log (reduce + (range 1024))))

(js/chrome.runtime.onInstalled.addListener
 (fn []
   (js/console.log "Installed!")))