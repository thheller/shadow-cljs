(ns shadow.cljs.ui.app
  (:require [shadow.markup.react :as html :refer (defstyled)]
            [shadow.dom :as dom]
            [shadow.api :refer (ns-ready)]
            ["react-dom" :as rdom :refer (render)]
            [shadow.cljs.ui.build-list :as build-list]))

(def root (dom/by-id "root"))

(defstyled app-container :div
  [env]
  {:padding 10})

(defstyled app-title :div
  [env]
  {:font-weight "bold"
   :margin-bottom 10})

(defstyled main-container :div
  [env]
  {:display "flex"})

(defstyled main-menu :div
  [env]
  {:width 180})

(defstyled main-content :div
  [env]
  {:flex 1})

(defn app []
  (app-container
    (app-title "shadow-cljs")
    (main-container
      (main-menu
        (build-list/container {}))
      (main-content "bar"))))

(defn start []
  (js/console.log "start ...")
  (render (app) root))

(defn stop []
  (rdom/unmountComponentAtNode root)
  (js/console.log "stop"))

(defn init []
  (js/console.log "init")
  (start))

(ns-ready)