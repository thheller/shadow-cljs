(ns shadow.cljs.build-report.ui
  {:dev/always true}
  (:require
    [cljs.reader :as reader]
    [goog.format :as gf]
    [goog.string.format]
    [goog.string :refer (format)]
    [goog.math :as gm]
    [goog.style :as gs]
    [goog.positioning :as gpos]
    [shadow.grove :as sg :refer (defc <<)]
    [shadow.grove.kv :as kv]
    [shadow.grove.events :as ev]
    [loom.graph :as lg]
    [loom.alg :as la]
    [loom.graph :as g]))

(defn filesize [size]
  (gf/numBytesToString size 2 true true))

(defn conj-set [x y]
  (if (nil? x)
    #{y}
    (conj x y)))

(defc ui-group-item [item-id]
  (bind {:keys [resource-name optimized-size] :as item}
    (sg/kv-lookup ::group-item item-id))

  (event ::group-item-enter! [env ev e]
    (let [el (.. e -target)
          rect (gs/getBounds el)]
      (sg/dispatch-up! env (assoc ev :rect rect))))

  (render
    (<< [:tr.group-item {:on-mouseenter {:e ::group-item-enter! :item-id item-id}
                         :on-mouseleave {:e ::group-item-leave!}}
         [:td resource-name]
         [:td.numeric (filesize optimized-size)]])))

(defc ui-group [group-id]

  (bind {:keys [npm-info pom-info group-name group-type group-pct items optimized-size expanded is-duplicate] :as row}
    (sg/kv-lookup ::group group-id))

  (render
    (<< [:tr.group__row
         {:on-click {:e ::toggle-group! :group-id group-id}}
         [:td.group__expand-toggle (if expanded "-" "+")]
         [:td
          {:class (when is-duplicate "group__duplicate")}
          (cond
            pom-info
            (let [{[id version] :coordinate} pom-info]
              (<< [:div.group__header (str id " @ mvn: " version)]))

            npm-info
            (<< [:div.group__header (str (:package-name npm-info) " @ npm: " (:version npm-info))])

            (= group-type :fs)
            (<< [:div.group__header [:b group-name]])

            :else
            (<< [:div.group__header group-name]))]

         [:td.numeric (filesize optimized-size)]
         [:td.numeric (format "%.1f %%" group-pct)]]

        (when expanded
          (<< [:tr
               [:td.group__expand-toggle]
               [:td.group__expand {:colSpan 2}
                [:div]
                [:table
                 [:thead
                  [:tr
                   [:th "Source"]
                   [:th.numeric "Optimized"]]]
                 [:tbody
                  (sg/simple-seq items ui-group-item)]]]
               [:td]])))))

(defc ui-module [mod-id]
  (bind {:keys [module-id js-size gzip-size groups]}
    (sg/kv-lookup ::module mod-id))

  (render
    (<< [:div.module
         [:div.module__title (str "Module: " (pr-str module-id) " [JS: " (filesize js-size) "] [GZIP: " (filesize gzip-size) "]")]
         [:table
          [:thead
           [:th]
           [:th "Group"]
           [:th.numeric "Optimized"]
           [:th.numeric "%"]]

          [:tbody
           (sg/simple-seq groups ui-group)]
          ]])))

(defc ui-hover-item [hover-item]
  (bind resources
    (sg/kv-lookup ::resource))

  (bind container-ref
    (sg/ref))

  (effect :mount [env]
    (let [rect (:rect hover-item)
          pos (gm/Coordinate.
                (+ 20 (.-left rect))
                (+ 20 (.-top rect) (.-height rect)))]
      (gpos/positionAtCoordinate
        pos
        @container-ref
        gpos/Corner.TOP_LEFT
        )))

  (render
    (let [{:keys [resource paths]} hover-item]

      (<< [:div.hover__container {:dom/ref container-ref}
           [:div.hover__title (:resource-name resource)]
           [:div.hover__explainer
            "was included in the build via the following entries. Traced from the resource (first) to the relevant configured :modules :entries resource (last). Only showing shortest path for each entry."]
           (sg/simple-seq paths
             (fn [{:keys [entry path] :as path}]

               (<< [:div.hover__require-trace
                    [:div "Dependency Trace:"]

                    [:div.hover__require-trace-items
                     (sg/simple-seq (reverse path)
                       (fn [resource-id]
                         (<< [:div.hover__require-trace-item
                              (str " - " (get-in resources [resource-id :resource-name]))])))]])
               ))]))))

(defc ui-root []
  (bind {:keys [display-modules hover-item]}
    (sg/kv-lookup ::root))

  (render
    (<< (sg/simple-seq display-modules ui-module)
        (when hover-item
          (ui-hover-item hover-item)
          ))))

(def rt-ref
  (sg/get-runtime ::ui))

(ev/reg-event rt-ref ::toggle-group!
  (fn [env {:keys [group-id]}]
    (update-in env [::group group-id :expanded] not)))

(ev/reg-event rt-ref ::group-item-enter!
  (fn [env {:keys [item-id rect]}]
    (let [{:keys [require-graph module-entries]} (::root env)

          rc-id (get-in env [::group-item item-id :resource-id])

          hover-item
          (reduce
            (fn [item entry-id]
              (let [path (la/shortest-path require-graph entry-id rc-id)]
                (if-not path
                  item
                  (update item :paths conj {:path path
                                            :entry-id entry-id}))))

            {:item-id item-id
             :rect rect
             :resource-id rc-id
             :paths []}

            module-entries)]

      (if-not (seq (:paths hover-item))
        env
        (assoc-in env [::root :hover-item] hover-item)))))

(ev/reg-event rt-ref ::group-item-leave!
  (fn [env {:keys [item-id]}]
    (assoc-in env [::root :hover-item] nil)))

(defonce root-el
  (js/document.getElementById "root"))

(defn ^:dev/after-load start []
  (sg/render rt-ref root-el (ui-root)))

(defn check-dupes [env]
  (let [npm-groups
        (->> (::group env)
             (vals)
             (filter #(= :npm (:group-type %))))

        group-dupes
        (reduce
          (fn [m {:keys [group-name group-id] :as group}]
            (update m group-name conj-set group-id))
          {}
          npm-groups)]

    (reduce-kv
      (fn [env group-name group-ids]
        (if-not (> (count group-ids) 1)
          env
          (reduce
            (fn [env group-id]
              (assoc-in env [::group group-id :is-duplicate] true))
            env
            group-ids)))
      env
      group-dupes)))

(defn init []
  (sg/add-kv-table rt-ref ::root
    {})

  (sg/add-kv-table rt-ref ::module
    {:primary-key :module-id
     :joins {:groups ::group}})

  (sg/add-kv-table rt-ref ::group
    {:primary-key :group-id
     :joins {:items ::group-item}})

  (sg/add-kv-table rt-ref ::group-item
    {:primary-key :group-item-id})

  (sg/add-kv-table rt-ref ::resource
    {:primary-key :resource-id})

  (let [{:keys [build-modules build-sources] :as data}
        (-> (js/document.querySelector "script[type=\"shadow/build-report\"]")
            (.-innerText)
            (reader/read-string))

        sources-by-name
        (->> build-sources
             (map (juxt :resource-name identity))
             (into {}))

        provide->rc
        (into {}
          (for [{:keys [provides resource-id]} build-sources
                provide provides]
            [provide resource-id]))

        require-graph
        (reduce
          (fn [g {:keys [requires resource-id]}]
            (reduce
              (fn [g ns]
                (if-let [other (get provide->rc ns)]
                  (g/add-edges g [resource-id other])
                  g))
              g
              requires))
          (lg/digraph)
          build-sources)

        entries
        (->> build-sources
             (filter :module-entry)
             (map :resource-id)
             (set))

        display-modules
        (->> build-modules
             (map (fn [{:keys [source-bytes module-id] :as mod}]
                    (let [total
                          (->> source-bytes vals (reduce + 0))

                          groups
                          (->> source-bytes
                               (map (fn [[resource-name optimized-size]]
                                      (let [{:keys [npm-info pom-info fs-root resource-id] :as src-info}
                                            (get sources-by-name resource-name)

                                            group-id
                                            (or (and npm-info [module-id :npm (:package-id npm-info) (:package-name npm-info)])
                                                (and pom-info [module-id :jar (:id pom-info) (str (:id pom-info))])
                                                (and fs-root [module-id :fs fs-root fs-root])
                                                [module-id :gen :gen "Generated Files"])]

                                        (assoc src-info
                                          :resource-name resource-name
                                          :group-item-id [group-id resource-id]
                                          :group-id group-id
                                          :optimized-size optimized-size))))
                               ;; sort all items so the sub-table is sorted
                               (sort-by :optimized-size >)
                               (group-by :group-id)
                               (map (fn [[group-id [item :as items]]]
                                      (let [group-size (->> items (map :optimized-size) (reduce + 0))]
                                        (assoc item
                                          :group-id group-id
                                          :group-type (nth group-id 1)
                                          :group-name (nth group-id 3)
                                          :group-pct (* 100 (double (/ group-size total)))
                                          :optimized-size group-size
                                          :item-count (count items)
                                          :items items))))
                               ;; then sort again by aggregate
                               (sort-by :optimized-size >)
                               (vec))]
                      (assoc mod :groups groups))))
             (vec))]

    (sg/kv-init rt-ref
      (fn [env]
        (-> env
            (update ::root merge
              {:build-modules build-modules
               :build-sources build-sources
               :module-entries entries
               :require-graph require-graph})
            (kv/merge-seq ::resource build-sources)
            (kv/merge-seq ::module display-modules [::root :display-modules])
            (check-dupes))))

    (start)))
