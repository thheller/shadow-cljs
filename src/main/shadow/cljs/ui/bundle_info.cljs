(ns shadow.cljs.ui.bundle-info
  (:require [clojure.string :as str]
            [shadow.api :refer (ns-ready)]
            [shadow.dom :as dom]
    #_["./bundle_renderer" :as x]
            [goog.format :as gf]
            [shadow.markup.react :as html :refer (defstyled)]
            ["react-dom" :as rdom]))


(defonce state-ref (atom {}))

(defonce root (dom/by-id "root"))

#_(defn sunburst []
    (let [{:keys [build-modules]}
          @state-ref

          all
          (->> (:build-modules @state-ref)
               (map :source-bytes)
               (reduce #(merge-with + %1 %2))
               (remove #(str/includes? (first %) "synthetic:"))
               (sort-by second)
               (clj->js))]

      (dom/append root
        [:div#container
         [:div#sequence]
         [:div#chart
          [:div#explanation
           {:style "visibility: hidden;"}
           [:span#percentage]]]])

      (x/createVisualization all)
      ))

(defn safe-add [x y]
  (if (nil? x)
    y
    (+ x y)))

(defn source-rows [{:keys [source-bytes]}]
  (->> source-bytes
       (reduce-kv
         (fn [idx name size]
           (let [parts (str/split name "/")]

             (reduce
               (fn [idx path]
                 (update idx path safe-add size))
               idx
               (->> (count parts)
                    (inc)
                    (range 1)
                    (map #(vec (take % parts)))))

             ))
         {})
       (map (fn [[path size]]
              {:path path
               :name (str/join "/" path)
               :size size}))
       (sort-by (fn [{:keys [path size]}]
                  [size (* -1 (count path))]))
       (reverse)
       (into [])))

(defstyled caption :caption [_]
  {:font-weight "bold"
   :text-align "left"})

(defstyled table :table [_]
  {:border-collapse "collapse"
   :width "100%"
   :margin-bottom 20
   "& td, & caption, & th"
   {:border-top "1px solid #eee"
    :padding [5 10]}})

(defstyled row-size :td [_]
  {:font-weight "bold"
   :text-align "right"
   :white-space "nowrap"
   :width 80})

(defstyled th :th [_]
  {:font-weight "normal"
   :text-align "right"
   :white-space "nowrap"
   :width 80})

(defstyled row-name :td [_]
  {})

(defn filesize [size]
  (gf/numBytesToString size 2 true true))

(defn overview []
  (let [sources-by-name
        (->> (:build-sources @state-ref)
             (map (juxt :resource-name identity))
             (into {}))]

    (html/div
      (for [{:keys [module-id js-size gzip-size] :as mod} (:build-modules @state-ref)]
        (table
          (caption (str "Module: " (pr-str module-id) " [JS: " (filesize js-size) "] [GZIP: " (filesize gzip-size) "]"))
          (html/thead
            (html/tr
              (th "Optimized")
              (th "Source")
              (html/th "")))

          (html/tbody
            (for [{:keys [name size] :as row} (source-rows mod)]
              (html/tr {:key name}
                (row-size (filesize size))
                (row-size
                  (when-let [{:keys [js-size] :as src} (get sources-by-name name)]
                    (filesize js-size)))
                (row-name name))
              )))))))

(defn start []
  (js/console.log "start")
  (rdom/render (overview) root))

(defn stop []
  (rdom/unmountComponentAtNode root)
  (js/console.log "stop"))

;; https://chrisbateman.github.io/webpack-visualizer/
;; https://bl.ocks.org/kerryrodden/766f8f6d31f645c39f488a0befa1e3c8

(defn ^:export init [bundle-data]
  (swap! state-ref merge bundle-data)
  (start))

(ns-ready)