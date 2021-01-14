(ns shadow.cljs.ui.components.build-page
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.model :as m]))

(defn ui-build-tabs)
[:div
 [:div.sm:hidden
  [:label.sr-only {:for "tabs"} "Select a tab"]
  [:select#tabs.block.w-full.focus:ring-indigo-500.focus:border-indigo-500.border-gray-300.rounded-md {:name "tabs"}
   [:option "My Account"]
   [:option "Company"]
   [:option {:selected "true"} "Team Members"]
   [:option "Billing"]]]
 [:div.hidden.sm:block
  [:nav.flex.space-x-4 {:aria-label "Tabs"}
   [:a.text-gray-600.hover:text-gray-800.px-3.py-2.font-medium.text-sm.rounded-md {:href "#"} "My Account"]
   [:a.text-gray-600.hover:text-gray-800.px-3.py-2.font-medium.text-sm.rounded-md {:href "#"} "Company"]
   [:a.bg-gray-200.text-gray-800.px-3.py-2.font-medium.text-sm.rounded-md {:href "#" :aria-current "page"} "Team Members"]
   [:a.text-gray-600.hover:text-gray-800.px-3.py-2.font-medium.text-sm.rounded-md {:href "#"} "Billing"]]]]