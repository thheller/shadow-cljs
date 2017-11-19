(ns shadow.cljs.ui.bundle-info
  (:require [clojure.string :as str]
            [shadow.api :refer (ns-ready)]
            [shadow.dom :as dom]))

(defonce state-ref (atom {}))

(defn start []
  (js/console.log "visualize-me please" @state-ref))

(defn stop []
  (js/console.log "stop"))

;; https://chrisbateman.github.io/webpack-visualizer/
;; https://bl.ocks.org/kerryrodden/766f8f6d31f645c39f488a0befa1e3c8

(defn ^:export init [bundle-data]
  (swap! state-ref merge bundle-data)
  (start))

(ns-ready)