(ns shadow.cljs.ui.bundle-info
  (:require [clojure.string :as str]
            [shadow.api :refer (ns-ready)]
            [shadow.dom :as dom]
            ["./bundle_renderer" :as x]))

(defonce state-ref (atom {}))

(defn start []
  (js/console.log "visualize-me please" @state-ref)
  (let [{:keys [build-modules]}
        @state-ref

        all
        (->> (:build-modules @state-ref)
             (map :source-bytes)
             (reduce #(merge-with + %1 %2))
             (remove #(str/includes? (first %) "synthetic:"))
             (sort-by second)
             (clj->js))]

    (x/createVisualization all)
    (js/console.log all)
    ))

(defn stop []
  (js/console.log "stop"))

;; https://chrisbateman.github.io/webpack-visualizer/
;; https://bl.ocks.org/kerryrodden/766f8f6d31f645c39f488a0befa1e3c8

(defn ^:export init [bundle-data]
  (swap! state-ref merge bundle-data)
  (start))

(ns-ready)