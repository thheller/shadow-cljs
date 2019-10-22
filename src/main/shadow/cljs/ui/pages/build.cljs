(ns shadow.cljs.ui.pages.build
  (:require
    [com.fulcrologic.fulcro.components :as fc :refer (defsc)]
    [shadow.markup.react :as html :refer (defstyled $)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.ui.util :as util]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.transactions :as tx]
    [shadow.cljs.ui.components.build-status :as build-status]
    ["react-table" :as rt :default ReactTable]
    [loom.graph :as g]
    [loom.alg :as ga]
    [clojure.string :as str]
    [shadow.cljs.ui.routing :as routing]
    [shadow.cljs.ui.fulcro-mods :as fm]
    [shadow.cljs.ui.components.build-panel :as build-panel]
    [shadow.cljs.ui.env :as env]))


(defn render-build-status-detail [build-status]
  (util/dump build-status))

(defstyled html-module-deps :div
  [env]
  {})

(defstyled html-module-dep :div
  [env]
  {:display "inline-block"
   :border "1px solid #ccc"
   :padding [0 5]})

(def rt-columns
  (-> [{:id "group-name"
        :Header "Name"
        :Cell (fn [^js row-obj]
                (let [{:keys [module-id] :as row}
                      (.-original row-obj)]
                  (name module-id)))}
       {:id "depends-on"
        :Header "Depends On"
        :Cell (fn [^js row-obj]
                (let [deps (.-value row-obj)]
                  (html-module-deps
                    (html/for [dep deps]
                      (html-module-dep {:key dep} (name dep))))))

        :accessor #(-> % :depends-on)}
       {:id "sources"
        :Header "Sources"
        :headerClassName "numeric"
        :className "numeric"
        :maxWidth 120
        :Cell (fn [^js row-obj]
                (.-value row-obj))
        :accessor #(-> % :sources count)}]
      (clj->js)))

(defn filter-row-by-type [filter ^js row]
  (or (= "all" (.-value filter))
      (= (.-value filter) (-> row .-_original :type name))))

(defn filter-row-by-resource-name [filter ^js row]
  (or (= "" (.-value filter))
      (str/includes? (-> row .-_original :resource-name) (.-value filter))))

(defn render-filter-by-type [^js x]
  (html/select
    {:onChange (fn [^js e]
                 (.onChange x (-> e .-target .-value)))
     :value (.-value x)}

    (html/option {:value "all"} "All")
    (html/option {:value "cljs"} "CLJS")
    (html/option {:value "goog"} "Closure JS")
    (html/option {:value "js"} "JS")
    (html/option {:value "shadow-js"} "shadow-js")))

(defn render-build-provides [this {::m/keys [build-provides build-info build-ns-summary] :as props}]
  (html/div
    (html/h1 "Build Namespaces")
    (html/select {:value (str (:ns build-ns-summary))
                  :onChange
                  (fn [^js e]
                    (fc/transact! this [(tx/inspect-build-ns {:ns (-> e .-target .-value symbol)})]))}

      (html/option {:key "" :value ""} "...")
      (html/for [ns build-provides]
        (html/option {:key ns :value (str ns)} (str ns))))

    (when build-ns-summary
      (let [{:keys [ns rc entry-paths]}
            build-ns-summary

            {:keys [resource-name]} rc]

        (html/div
          (html/h3 (str "Namespace: " ns " via: " resource-name))
          (html/h3 "Entries that led to the inclusion of this namespace")
          (html/for [path entry-paths]
            (html/div (->> path
                           (map str)
                           (str/join " -> ")))
            ))))))

(defn render-build-info [{:keys [sources modules] :as build-info}]
  (let [src-by-id
        (reduce #(assoc %1 (:resource-id %2) %2) {} sources)

        rt-sub-columns
        (-> [{:id "type"
              :Header "Type"
              :maxWidth 120
              :filterMethod filter-row-by-type
              :Filter render-filter-by-type
              :accessor #(pr-str (:type %))}
             {:id "resource-name"
              :Header "Name"
              :filterMethod filter-row-by-resource-name
              :Cell (fn [^js row-obj]
                      (.-value row-obj))
              :accessor #(:resource-name %)}]
            (clj->js))

        sub-row
        (fn sub-row [^js row]
          (let [{:keys [sources] :as row} (.-original row)

                source-arr
                (->> sources
                     (map (fn [src-id]
                            (get src-by-id src-id)))
                     (into-array))]

            (html/div
              ($ ReactTable
                #js {:data source-arr
                     :filterable true
                     :defaultFilterMethod (constantly true)
                     :columns rt-sub-columns
                     :showPagination false
                     :defaultPageSize (count sources)
                     :minRows 0
                     :className "-striped -highlight"}))))]

    (html/div
      (html/h1 "Build Info")
      (let [cm (count modules)]
        (if (> cm 1)
          (html/p (str (count sources) " Sources split into " cm " Modules"))
          (html/p (str (count sources) " Sources in one Module"))))

      ($ ReactTable
        #js {:data (into-array modules)
             :columns rt-columns
             :showPagination false
             :defaultPageSize (count modules)
             :minRows 0
             :expanderDefaults #js {:width 40}
             :className "-striped -highlight"
             :SubComponent sub-row
             })
      )))

(defsc BuildPage
  [this {::m/keys [build-id] :as props}]
  {:ident
   (fn []
     [::m/build-id build-id])

   :query
   (fn []
     [::m/build-id
      ::m/build-config-raw
      ::m/build-status
      ::m/build-info
      ::m/build-provides
      ::m/build-ns-summary
      ::m/build-worker-active])}

  (let [{::m/keys [build-status build-info build-provides build-config-raw build-worker-active]} props
        {:keys [status log]} build-status]

    (if-not build-id
      (html/div "Loading ...")
      (s/main-contents
        (s/page-title (name build-id))

        (s/build-section
          (s/build-section-title "Actions")
          (s/simple-toolbar
            (if build-worker-active
              (s/toolbar-actions
                (s/toolbar-action {:onClick #(fc/transact! this [(tx/build-watch-compile {:build-id build-id})])} "force-compile")
                (s/toolbar-action {:onClick #(fc/transact! this [(tx/build-watch-stop {:build-id build-id})])} "stop watch"))

              (s/toolbar-actions
                (s/toolbar-action {:onClick #(fc/transact! this [(tx/build-watch-start {:build-id build-id})])} "start watch")
                (s/toolbar-action {:onClick #(fc/transact! this [(tx/build-compile {:build-id build-id})])} "compile")
                (s/toolbar-action {:onClick #(fc/transact! this [(tx/build-release {:build-id build-id})])} "release")
                ))))

        #_ (let [{::m/keys [http-url]} (::m/build-http-server props)]
          (when http-url
            (s/build-section
              (s/build-section-title "HTTP")
              (html/a {:href http-url :target "_blank"} http-url)
              )))

        (s/build-section
          (s/build-section-title "Status")
          (build-status/render-build-status build-status))

        (when (or (= :failed status)
                  (= :completed status))

          (s/build-section
            (s/build-section-title "Log")
            (html/for [msg log]
              (s/build-log-entry {:key msg} msg))))

        (when (and (= :completed status)
                   (map? build-info))
          (html/div
            (render-build-provides this props)
            (render-build-info build-info)))
        #_(html/div
            (s/build-section "Config")
            (s/build-config
              (util/dump build-config-raw)
              ))))))

(def ui-build-page (fc/factory BuildPage {:keyfn ::m/build-id}))

(routing/register ::ui-model/root-router ::m/build-id
  {:class BuildPage
   :factory ui-build-page
   :keyfn ::m/build-id})

(defsc Page [this props]
  {:ident
   (fn []
     [::ui-model/page-builds 1])

   :query
   (fn []
     [{[::ui-model/build-list '_]
       (fc/get-query build-panel/BuildPanel)}])

   :initial-state
   (fn [p]
     {})}

  (s/main-contents
    (s/cards-title "Builds")

    (html/for [build (::ui-model/build-list props)]
      (build-panel/ui-build-panel build))))

(def ui-page (fc/factory Page {}))

(routing/register ::ui-model/root-router ::ui-model/page-builds
  {:class Page
   :factory ui-page})

(defn route [[build-id :as tokens]]
  (fc/transact! env/app
    [(tx/select-build {:build-id (keyword build-id)})
     (routing/set-route {:router ::ui-model/root-router
                         :ident [::ui-model/page-builds 1]})]))

(defn route-build [[build-id :as tokens]]
  (fc/transact! env/app
    [(tx/select-build {:build-id (keyword build-id)})
     (routing/set-route {:router ::ui-model/root-router
                         :ident [::m/build-id (keyword build-id)]})]))

(defn make-ns-summary [{:keys [modules sources] :as build-info} the-ns]
  (let [entries
        (->> modules
             (mapcat :entries)
             (into #{})
             (sort)
             (into []))

        provide->source
        (->> (for [{:keys [provides] :as rc} sources
                   provide provides]
               [provide rc])
             (into {}))

        the-rc
        (get provide->source the-ns)

        edges
        (for [{:keys [deps provides]} sources
              provide provides
              dep deps]
          [provide dep])

        graph
        (apply g/digraph edges)

        entry-paths
        (into []
          (for [entry entries
                :let [path (ga/shortest-path graph entry the-ns)]
                :when (seq path)]
            (vec path)))
        ]

    {:ns the-ns
     :rc the-rc
     :entry-paths entry-paths
     }))

(fm/handle-mutation tx/inspect-build-ns
  {:refresh
   (fn []
     [::m/build-ns-summary])
   :state-action
   (fn [state {:keys [ref] :as env} {:keys [ns] :as params}]
     (let [{::m/keys [build-info] :as obj}
           (get-in state ref)

           ns-summary
           (make-ns-summary build-info ns)]

       (update-in state ref assoc ::m/build-ns-summary ns-summary)
       ))})

(defn init [])
