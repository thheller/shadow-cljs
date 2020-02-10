(ns shadow.cljs.ui.components.builds
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.model :as m]
    ))

(defc ui-builds-page []
  [{::m/keys [builds]}
   (sg/query-root
     [{::m/builds
       [::m/build-id]}])]

  (<< [:div.flex-1.overflow-auto
       [:div.p-2
        (sg/render-seq builds ::m/build-id
          (fn [{::m/keys [build-id] :as item}]
            (<< [:div.py-1
                 [:a.font-bold {:href (str "/build/" (name build-id))} (name build-id)]])))
        ]]))

(defc ui-build-overview [build-ident]
  [{::m/keys [build-sources-sorted] :as data}
   (sg/query-ident build-ident
     [::m/build-sources-sorted])

   state-ref (atom {:selected nil})

   selected (sg/watch state-ref [:selected])

   id->src
   (into {} (map (juxt :resource-id identity)) build-sources-sorted)

   id->idx
   (reduce-kv
     (fn [m idx {:keys [resource-id] :as v}]
       (assoc m resource-id idx))
     {}
     build-sources-sorted)

   ::highlight
   (fn [env e resource-id]
     (swap! state-ref assoc :selected resource-id))

   render-source-entry
   (fn [{:keys [resource-id resource-name] :as item}]
     (let [selected? (= resource-id selected)]
       (<< [:div.text-xs
            {:class (when selected? "font-bold")
             :on-mouseenter [::highlight resource-id]}
            resource-name])))]

  (<< [:div.p-2
       [:div.py-2.text-xl (count build-sources-sorted) " Namespaces used in build"]
       [:div.flex
        [:div.flex-1 "left"]
        [:div
         (sg/render-seq build-sources-sorted nil render-source-entry)]
        [:div.flex-1 "right"]]]))

(defc ui-build-page [build-ident]
  [data
   (sg/query-ident build-ident
     [:db/ident
      ::m/build-id
      ::m/build-target
      ::m/build-worker-active
      ::m/build-status])

   ::m/build-watch-compile! sg/tx
   ::m/build-watch-start! sg/tx
   ::m/build-watch-stop! sg/tx
   ::m/build-compile! sg/tx
   ::m/build-release! sg/tx
   ::m/build-release-debug! sg/tx]

  (let [{::m/keys [build-id build-target build-status build-worker-active]} data]
    (<< [:div
         [:h1.text-xl.px-2.py-4 (name build-id) " - " (name build-target)]
         [:div.p-2
          (if build-worker-active
            (<< [:button.py-2.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
                 {:on-click [::m/build-watch-compile! build-id]}
                 "force compile"]
                [:button.ml-2.py-2.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
                 {:on-click [::m/build-watch-stop! build-id]}
                 "stop watch"])

            (<< [:button.py-2.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
                 {:on-click [::m/build-watch-start! build-id]}
                 "start watch"]
                [:button.ml-2.py-2.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
                 {:on-click [::m/build-compile! build-id]}
                 "compile"]
                [:button.ml-2.py-2.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
                 {:on-click [::m/build-release! build-id]}
                 "release"]
                [:button.ml-2.py-2.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
                 {:on-click [::m/build-release-debug! build-id]}
                 "release debug"]))]]

        [:div.p-2
         [:div.text-lg "Build Status"]
         (build-status/render-build-status build-status)]

        [:div.flex-1.overflow-auto
         (when (= :completed (:status build-status))
           (<< (build-status/render-build-log build-status)
               #_(ui-build-overview build-ident)))]
        )))


