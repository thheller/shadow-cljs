(ns shadow.cljs.ui.chrome-ext.panel
  (:require
    [goog.object :as gobj]
    [shadow.dom :as dom]))

(defn init []
  (js/chrome.devtools.inspectedWindow.eval
    "shadow.cljs.devtools.client.env.devtools_info()"
    (fn [^js info err]
      (if err
        (do (js/console.log "inspected-window not shadow-cljs" err)
            (dom/append [:h1 {:style "text-align: center;"} "Not a shadow-cljs development build."]))
        (dom/append
          [:iframe#frame
           {:src (str "http://localhost:" (gobj/get info "server-port") "/build/" (gobj/get info "build-id"))}]))
      )))