(ns shadow.cljs.ui.chrome-ext.devtools)

(defn init []
  (js/chrome.devtools.panels.create
    "shadow-cljs"
    ""
    "panel.html"
    (fn [^js panel]
      )))
