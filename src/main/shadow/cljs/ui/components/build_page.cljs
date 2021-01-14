(ns shadow.cljs.ui.components.build-page
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.model :as m]))

(defc ui-page [build-ident]

  (bind data
    (sg/query-ident build-ident
      [:db/ident
       ::m/build-id
       ::m/build-target
       ::m/build-worker-active
       ::m/build-status]))

  (event ::m/build-watch-compile! sg/tx)
  (event ::m/build-watch-start! sg/tx)
  (event ::m/build-watch-stop! sg/tx)
  (event ::m/build-compile! sg/tx)
  (event ::m/build-release! sg/tx)
  (event ::m/build-release-debug! sg/tx)

  (render
    (let [tab :runtimes
          tab-selected "bg-gray-200 text-gray-800 px-3 py-2 font-medium text-sm rounded-md"
          tab-normal "text-gray-600 hover:text-gray-800 px-3 py-2 font-medium text-sm rounded-md"]
      (<< [:div.flex-1.overflow-auto.py-2.sm:px-2
           [:div.max-w-7xl
            [:div.flex.flex-col
             [:div.align-middle.min-w-full
              [:div
               [:nav.flex.space-x-4 {:aria-label "Tabs"}
                [:a.text-gray-600.hover:text-gray-800.px-3.py-2.font-medium.text-sm.rounded-md
                 {:class (if (= tab :status) tab-selected tab-normal)
                  :href "#status"}
                 "Status"]
                [:a.text-gray-600.hover:text-gray-800.px-3.py-2.font-medium.text-sm.rounded-md
                 {:class (if (= tab :runtimes) tab-selected tab-normal)
                  :href "#runtimes"}
                 "Runtimes"]]]
              (case tab
                :status
                (<< [:div "status"])

                :runtimes
                (<< [:div "runtimes"])

                [:div "tab not found"])]]]]))))