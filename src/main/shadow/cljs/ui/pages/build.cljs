(ns shadow.cljs.ui.pages.build
  (:require
    [fulcro.client.primitives :as fp :refer (defsc)]
    [shadow.markup.react :as html :refer (defstyled $)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.ui.util :as util]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.transactions :as tx]
    [shadow.cljs.ui.components.build-status :as build-status]
    ["react-table" :as rt :default ReactTable]
    [clojure.string :as str]
    [shadow.cljs.ui.routing :as routing]
    [shadow.cljs.ui.fulcro-mods :as fm]))


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

(def build-http-server
  (util/ident-gen ::m/http-server-id))

(defsc BuildOverview
  [this
   {::m/keys [build-id build-status build-info build-config-raw build-worker-active] :as props}]
  {:ident
   [::m/build-id ::m/build-id]

   :query
   [::m/build-config-raw
    ::m/build-id
    ::m/build-status
    ::m/build-info
    {::m/build-http-server (build-http-server
                             [::m/http-url])}
    ::m/build-worker-active]}

  (if-not build-id
    (html/div "Loading ...")
    (s/main-contents
      (s/page-title (name build-id))

      (s/build-section
        (s/build-section-title "Actions")
        (s/simple-toolbar
          (if build-worker-active
            (s/toolbar-actions
              (s/toolbar-action {:onClick #(fp/transact! this [(tx/build-watch-compile {:build-id build-id})])} "force-compile")
              (s/toolbar-action {:onClick #(fp/transact! this [(tx/build-watch-stop {:build-id build-id})])} "stop watch"))

            (s/toolbar-actions
              (s/toolbar-action {:onClick #(fp/transact! this [(tx/build-watch-start {:build-id build-id})])} "start watch")
              (s/toolbar-action {:onClick #(fp/transact! this [(tx/build-compile {:build-id build-id})])} "compile")
              (s/toolbar-action {:onClick #(fp/transact! this [(tx/build-release {:build-id build-id})])} "release")
              ))))

      (let [{::m/keys [http-url]} (::m/build-http-server props)]
        (when http-url
          (s/build-section
            (s/build-section-title "HTTP")
            (html/a {:href http-url :target "_blank"} http-url)
            )))

      (s/build-section
        (s/build-section-title "Status")
        (build-status/render-build-status build-status))

      (when (and (= :completed (:status build-status))
                 (map? build-info))
        (render-build-info build-info))
      #_(html/div
          (s/build-section "Config")
          (s/build-config
            (util/dump build-config-raw)
            )))))

(def ui-build-overview (fp/factory BuildOverview {:keyfn ::m/build-id}))

(routing/register ::ui-model/root-router ::m/build-id
  {:class BuildOverview
   :factory ui-build-overview
   :keyfn ::m/build-id})




(defn route [r [build-id :as tokens]]
  (fp/transact! r
    [(tx/select-build {:build-id (keyword build-id)})
     (routing/set-route {:router ::ui-model/root-router

                         :ident [::m/build-id (keyword build-id)]})]))

(defn init [])
