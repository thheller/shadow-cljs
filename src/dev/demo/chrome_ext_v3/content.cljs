(ns demo.chrome-ext-v3.content
  (:require [cljs.core.async :refer [chan close! go-loop]]
            [chromex.ext.tabs :as tabs]
            [chromex.ext.web-navigation :as web-navigation]
            [chromex.chrome-event-channel :refer [make-chrome-event-channel]]))

(defn init []
  (js/console.log "▶❤◀")
  (js/console.log (js/chrome.runtime.getURL "")))

(defn ^:dev/after-load call-me-maybe []
  (js/console.warn "actually called!"))