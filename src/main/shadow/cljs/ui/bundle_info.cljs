(ns shadow.cljs.ui.bundle-info
  (:require
    [clojure.string :as str]
    [shadow.api :refer (ns-ready)]
    [shadow.dom :as dom]
    [goog.format :as gf]
    [shadow.markup.react :as html :refer ($ defstyled)]
    ["react-dom" :as rdom]
    ["react-table" :as rt :default ReactTable]
    [goog.string.format] ;; FIXME: implement :as format and make it callable just like JS
    [goog.string :refer (format)]
    ))

(defonce state-ref (atom {}))

(defonce root (dom/by-id "root"))

(defn filesize [size]
  (gf/numBytesToString size 2 true true))

(defstyled main-table-container :div [_]
  {:max-width 800})

(defstyled sub-table-container :div [_]
  {:padding [5 70 20 40]})

(defstyled sub-table-header :div [_]
  {:font-size "1.3em"
   :font-weight "bold"
   :margin-bottom 10})

(def rt-columns
  (-> [{:id "group-name"
        :Header "Name"
        :Cell (fn [^js row-obj]
                (let [{:keys [group-id group-name] :as row}
                      (.-original row-obj)]
                  (if (= :prj group-id)
                    (html/b group-name)
                    group-name)))}
       {:id "optimized-size"
        :Header "Optimized"
        :headerClassName "numeric"
        :className "numeric"
        :maxWidth 120
        :Cell (fn [^js row-obj]
                (filesize (.-value row-obj)))
        :accessor #(:optimized-size %)}
       {:id "group-ptc"
        :Header "%"
        :headerClassName "numeric"
        :className "numeric"
        :maxWidth 70
        :Cell (fn [^js row-obj]
                (format "%.1f %%" (.-value row-obj)))
        :accessor #(:group-pct %)}]
      (clj->js)))

(def rt-sub-columns
  (-> [{:id "resource-name"
        :Header "Name"
        :accessor #(:resource-name %)}
       {:id "optimized-size"
        :Header "Optimized"
        :headerClassName "numeric"
        :className "numeric"
        :maxWidth 120
        :Cell (fn [^js row-obj]
                (filesize (.-value row-obj)))
        :accessor #(:optimized-size %)}]
      (clj->js)))

(defn overview []
  (let [sources-by-name
        (->> (:build-sources @state-ref)
             (map (juxt :resource-name identity))
             (into {}))

        {:keys [build-sources build-modules]}
        @state-ref

        display-modules
        (->> build-modules
             (map (fn [{:keys [source-bytes] :as mod}]
                    (let [total
                          (->> source-bytes vals (reduce + 0))

                          rows
                          (->> source-bytes
                               (map (fn [[resource-name optimized-size]]
                                      (let [{:keys [npm-info pom-info] :as src-info}
                                            (get sources-by-name resource-name)]
                                        (merge src-info
                                          {:resource-name resource-name
                                           :group
                                           (or (and npm-info [:npm (:package-name npm-info)])
                                               (and pom-info [:jar (str (:id pom-info))])
                                               [:prj "Project Files"])
                                           :optimized-size optimized-size}))))
                               ;; sort all items so the sub-table is sorted
                               (sort-by :optimized-size >)
                               (group-by :group)
                               (map (fn [[group [item :as items]]]
                                      (let [[group-id group-name] group
                                            group-size (->> items (map :optimized-size) (reduce + 0))]
                                        (assoc item
                                          :group-id group-id
                                          :group-name group-name
                                          :optimized-size group-size
                                          :group-pct (* 100 (double (/ group-size total)))
                                          :item-count (count items)
                                          :items items))))
                               ;; then sort again by aggregate
                               (sort-by :optimized-size >)
                               (into-array))]
                      (assoc mod :rows rows))
                    )))


        sub-row
        (fn sub-row [^js row]
          (let [{:keys [npm-info pom-info resource-name item-count items] :as row}
                (.-original row)]

            (sub-table-container {}

              (when pom-info
                (sub-table-header (pr-str (:coordinate pom-info))))

              (when npm-info
                (sub-table-header (str "npm: " (:package-name npm-info) "@" (:version npm-info))))

              ($ ReactTable
                #js {:data (into-array items)
                     :columns rt-sub-columns
                     :showPagination false
                     :defaultPageSize 250
                     :minRows item-count
                     :className "-sriped -highlight"}))))]

    (html/div
      (for [{:keys [module-id js-size gzip-size rows] :as mod}
            display-modules]
        (main-table-container {:key (name module-id)}
          (html/h3 (str "Module: " (pr-str module-id) " [JS: " (filesize js-size) "] [GZIP: " (filesize gzip-size) "]"))

          ($ ReactTable
            #js {:data rows
                 :columns rt-columns
                 :showPagination false
                 :defaultPageSize 250
                 :minRows (count rows)
                 :expanderDefaults #js {:width 40}
                 :className "-sriped -highlight"
                 :SubComponent sub-row
                 }))))))

(defn ^:dev/after-load start []
  (js/console.log "start")
  (rdom/render (overview) root))

(defn ^:dev/before-load stop []
  (rdom/unmountComponentAtNode root)
  (js/console.log "stop"))

;; https://chrisbateman.github.io/webpack-visualizer/
;; https://bl.ocks.org/kerryrodden/766f8f6d31f645c39f488a0befa1e3c8

(defn ^:export init [bundle-data]
  (swap! state-ref merge bundle-data)
  (start))

(ns-ready)