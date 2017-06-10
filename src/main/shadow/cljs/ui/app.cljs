(ns shadow.cljs.ui.app
  (:require [shadow.markup.react :as html :refer (defstyled)]
            [shadow.dom :as dom]
            [shadow.api :refer (ns-ready)]
            [shadow.cljs.ui.build-list :as build-list]
            [shadow.vault.dom :as vdom]
            [shadow.vault.store :as store]))


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

(def data-ref (store/empty))

(defn start []
  (vdom/mount root (app) (store/context {} data-ref [])))

(defn stop []
  (js/console.log "stop")
  (vdom/unmount root))

(defn init []
  (js/console.log "init")
  (start))

(ns-ready)