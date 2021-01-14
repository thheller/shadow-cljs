(ns shadow.cljs.ui.components.build-page
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.model :as m]))

(defn build-buttons [build-id build-worker-active]
  (if build-worker-active
    (<< [:div
         [:div.-mt-px.flex.divide-x.divide-gray-200
          [:div.w-0.flex-1.flex
           [:button.relative.w-0.flex-1.inline-flex.items-center.justify-center.py-4.text-sm.text-gray-700.font-medium.border.border-transparent.hover:text-gray-500.focus:outline-none
            {:on-click {:e ::m/build-watch-compile! :build-id build-id}}
            [:svg.w-5.h-5.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M11.933 12.8a1 1 0 000-1.6L6.6 7.2A1 1 0 005 8v8a1 1 0 001.6.8l5.333-4zM19.933 12.8a1 1 0 000-1.6l-5.333-4A1 1 0 0013 8v8a1 1 0 001.6.8l5.333-4z"}]]
            [:span.ml-2 "Force"]]]
          [:div.-ml-px.w-0.flex-1.flex
           [:button.relative.w-0.flex-1.inline-flex.items-center.justify-center.py-4.text-sm.text-gray-700.font-medium.border.border-transparent.hover:text-gray-500.focus:outline-none
            {:on-click {:e ::m/build-watch-stop! :build-id build-id}}
            [:svg.w-5.h-5.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M21 12a9 9 0 11-18 0 9 9 0 0118 0z"}]
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M9 10a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1h-4a1 1 0 01-1-1v-4z"}]]
            [:span.ml-2 "Stop"]]]
          [:div.-ml-px.w-0.flex-1.flex
           [:a.relative.w-0.flex-1.inline-flex.items-center.justify-center.py-4.text-sm.text-gray-700.font-medium.border.border-transparent.rounded-br-lg.hover:text-gray-500
            {:href (str "/build/" (name build-id))}
            [:svg.w-5.h-5.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"}]]
            [:span.ml-2 "View"]]]]])

    (<< [:div
         [:div.-mt-px.flex.divide-x.divide-gray-200
          [:div.w-0.flex-1.flex
           [:button.relative.w-0.flex-1.inline-flex.items-center.justify-center.py-4.text-sm.text-gray-700.font-medium.border.border-transparent.hover:text-gray-500.focus:outline-none
            {:on-click {:e ::m/build-watch-start! :build-id build-id}}
            [:svg.w-5.h-5.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z"}]
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M21 12a9 9 0 11-18 0 9 9 0 0118 0z"}]]
            [:span.ml-2 "Watch"]]]
          [:div.-ml-px.w-0.flex-1.flex
           [:button.relative.w-0.flex-1.inline-flex.items-center.justify-center.py-4.text-sm.text-gray-700.font-medium.border.border-transparent.hover:text-gray-500.focus:outline-none
            {:on-click {:e ::m/build-release! :build-id build-id}}
            [:svg.w-5.h-5.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"}]]
            [:span.ml-2 "Release"]]]
          [:div.-ml-px.w-0.flex-1.flex
           [:a.relative.w-0.flex-1.inline-flex.items-center.justify-center.py-4.text-sm.text-gray-700.font-medium.border.border-transparent.rounded-br-lg.hover:text-gray-500
            {:href (str "/build/" (name build-id))}
            [:svg.w-5.h-5.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14"}]]
            [:span.ml-2 "View"]]]]])))