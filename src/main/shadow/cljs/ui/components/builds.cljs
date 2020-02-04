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
    (<< [:div.flex-1.overflow-auto
         [:h1.text-xl.px-2.py-4 (name build-id) " - " (name build-target)]
         [:div
          [:div.p-2.text-lg.font-bold "Actions"]
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
          (build-status/render-build-status build-status)
          (when (= :completed (:status build-status))
            (build-status/render-build-log build-status))
          ]])))
