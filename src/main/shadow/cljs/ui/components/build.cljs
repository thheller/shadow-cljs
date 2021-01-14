(ns shadow.cljs.ui.components.build
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.builds :as builds]))

(defc ui-page [build-ident]

  (bind {::m/keys [build-runtimes build-status] :as data}
    (sg/query-ident build-ident
      [::m/build-status
       ::m/build-runtimes]))

  (render
    ; FIXME: where should we store active-tab?
    (let [tab :status
          tab-selected "bg-gray-200 text-gray-800 px-3 py-2 font-medium text-sm rounded-md"
          tab-normal "text-gray-600 hover:text-gray-800 px-3 py-2 font-medium text-sm rounded-md"]
      (<< [:div.flex-1.overflow-auto.py-2.sm:px-2
           [:div.max-w-7xl
            [:div.flex.flex-col
             [:div.align-middle.min-w-full
              [:div
               [:nav.flex.space-x-4 {:aria-label "Tabs"}
                [:a
                 {:class (if (= tab :status) tab-selected tab-normal)
                  :href "#status"}
                 "Status"]
                [:a
                 {:class (if (= tab :runtimes) tab-selected tab-normal)
                  :href "#runtimes"}
                 "Runtimes"]]]
              (case tab
                :status
                (<< [:div.pt-2
                     (builds/build-card build-ident)
                     [:div.bg-white.shadow.sm:rounded-lg.mt-2
                      [:div.border-t.border-gray-200.px-4.py-4
                       (build-status/render-build-status-full build-status)]]])

                :runtimes
                (<< [:div.mt-2.align-middle.min-w-full.shadow.md:rounded-lg
                     (runtimes/ui-runtime-listing build-runtimes)])

                [:div "tab not found"])]]]]))))