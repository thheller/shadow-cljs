(ns shadow.cljs.ui.app
  (:require [shadow.markup.react :as html :refer (defstyled)]
            [shadow.dom :as dom]
            [shadow.api :refer (ns-ready)]
            ["react-dom" :refer (render)]))

(defn app []
  (html/h1 "hello world"))

(defn start []
  (js/console.log "start ...")
  (render (app) (dom/by-id "root")))

(defn stop []
  (js/console.log "stop"))

(defn init []
  (js/console.log "init")
  (start))

(ns-ready)