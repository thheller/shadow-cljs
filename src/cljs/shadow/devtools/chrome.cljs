(ns shadow.devtools.chrome
  (:require [shadow.util :refer (log)]
            [shadow.dom :as dom]))

(defn setup-panel [panel]
  (js/panel.onShown.addListener (fn [window]
                                  (log "panel opened" window)
                                  (dom/append (js/window.document.getElementById "message-container") "yo wassup")
                                  ))
  (log "chrome ext loaded" panel))



