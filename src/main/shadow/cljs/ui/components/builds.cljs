(ns shadow.cljs.ui.components.builds
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
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"}]]
            [:span.ml-2 "Rerun"]]]
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
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M13 9l3 3m0 0l-3 3m3-3H8m13 0a9 9 0 11-18 0 9 9 0 0118 0z"}]]
            [:span.ml-2 "Run"]]]
          [:div.-ml-px.w-0.flex-1.flex
           [:button.relative.w-0.flex-1.inline-flex.items-center.justify-center.py-4.text-sm.text-gray-700.font-medium.border.border-transparent.hover:text-gray-500.focus:outline-none
            {:on-click {:e ::m/build-compile! :build-id build-id}}
            [:svg.w-5.h-5.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M15 13l-3 3m0 0l-3-3m3 3V8m0 13a9 9 0 110-18 9 9 0 010 18z"}]]
            [:span.ml-2 "Compile"]]]
          [:div.-ml-px.w-0.flex-1.flex
           [:button.relative.w-0.flex-1.inline-flex.items-center.justify-center.py-4.text-sm.text-gray-700.font-medium.border.border-transparent.hover:text-gray-500.focus:outline-none
            {:on-click {:e ::m/build-release! :build-id build-id}}
            [:svg.w-5.h-5.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M20 7l-8-4-8 4m16 0l-8 4m8-4v10l-8 4m0-10L4 7m8 4v10M4 7v10l8 4"}]]
            [:span.ml-2 "Release"]]]
          [:div.-ml-px.w-0.flex-1.flex
           [:button.relative.w-0.flex-1.inline-flex.items-center.justify-center.py-4.text-sm.text-gray-700.font-medium.border.border-transparent.hover:text-gray-500.focus:outline-none
            {:on-click {:e ::m/build-release-debug! :build-id build-id}}
            [:svg.w-5.h-5.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
             [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M14 10l-2 1m0 0l-2-1m2 1v2.5M20 7l-2 1m2-1l-2-1m2 1v2.5M14 4l-2-1-2 1M4 7l2-1M4 7l2 1M4 7v2.5M12 21l-2-1m2 1l2-1m-2 1v-2.5M6 18l-2-1v-2.5M18 18l2-1v-2.5"}]]
            [:span.ml-2 "Debug"]]]]])))

(defc ui-builds-entry [build-ident]
  (bind {::m/keys [build-id build-worker-active build-warnings-count build-status] :as data}
    (sg/query-ident build-ident
      [::m/build-id
       ::m/build-worker-active
       ::m/build-warnings-count
       {::m/build-status
        [:status]}]))

  (render
    (let [{:keys [status]} build-status]
      (<< [:div.border-b.bg-white.p-4.flex
           [:div.self-center.pr-4
            [:a.cursor-pointer {:href (str "/build/" (name build-id))}
             (case status
               :compiling
               (<< [:span.h-6.w-6.bg-blue-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                    [:span.h-3.w-3.bg-blue-400.rounded-full]])

               :completed
               (if (zero? build-warnings-count)
                 (<< [:span.h-6.w-6.bg-green-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                      [:span.h-3.w-3.bg-green-400.rounded-full]])
                 (<< [:span.h-6.w-6.bg-yellow-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                      [:span.h-3.w-3.bg-yellow-400.rounded-full]]))

               :failed
               (<< [:span.h-6.w-6.bg-red-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                    [:span.h-3.w-3.bg-red-400.rounded-full]])

               :inactive
               (<< [:span.h-6.w-6.bg-gray-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                    [:span.h-3.w-3.bg-gray-400.rounded-full]])

               :pending
               (<< [:span.h-6.w-6.bg-blue-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                    [:span.h-3.w-3.bg-blue-400.rounded-full]])

               (<< [:span.h-6.w-6.bg-gray-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                    [:span.h-3.w-3.bg-gray-400.rounded-full]]))]]
           [:div.flex-1
            [:div.pb-1
             [:a.font-bold.text-lg {:href (str "/build/" (name build-id))} (name build-id)]]
            [:div
             (build-buttons build-id build-worker-active)]]]))))

(defc build-card [ident]

  (event ::m/build-compile! sg/tx)
  (event ::m/build-watch-stop! sg/tx)
  (event ::m/build-watch-compile! sg/tx)
  (event ::m/build-release-debug! sg/tx)

  (bind {::m/keys [build-status build-id build-target build-warnings-count build-worker-active] :as data}
    (sg/query-ident ident
      [::m/build-id
       ::m/build-target
       ::m/build-worker-active
       ::m/build-warnings-count
       ::m/build-status
       ::m/build-config-raw]))

  (render
    (let [{:keys [status]} build-status]
      (<< [:div.bg-white.overflow-hidden.shadow.sm:rounded-lg
           [:div.p-5.border-b.border-gray-200
            [:div.flex.items-center
             [:div.flex-shrink-0
              (case status
                :compiling
                (<< [:span.h-6.w-6.bg-blue-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                     [:span.h-3.w-3.bg-blue-400.rounded-full]])

                :completed
                (if (zero? build-warnings-count)
                  (<< [:span.h-6.w-6.bg-green-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                       [:span.h-3.w-3.bg-green-400.rounded-full]])
                  (<< [:span.h-6.w-6.bg-yellow-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                       [:span.h-3.w-3.bg-yellow-400.rounded-full]]))

                :failed
                (<< [:span.h-6.w-6.bg-red-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                     [:span.h-3.w-3.bg-red-400.rounded-full]])

                :inactive
                (<< [:span.h-6.w-6.bg-gray-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                     [:span.h-3.w-3.bg-gray-400.rounded-full]])

                :pending
                (<< [:span.h-6.w-6.bg-blue-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                     [:span.h-3.w-3.bg-blue-400.rounded-full]])

                ;default
                (<< [:span.h-6.w-6.bg-gray-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
                     [:span.h-3.w-3.bg-gray-400.rounded-full]]))]
             [:div.ml-5.w-0.flex-1
              [:dl
               [:dt.text-sm.font-medium.text-gray-500.truncate (name build-target)]
               [:dd
                [:a.text-lg.font-medium.text-gray-900 {:href (str "/build/" (name build-id))} (name build-id)]]]]]]
           (build-buttons build-id build-worker-active)]))))

(defc ui-builds-page []
  (bind {::m/keys [builds]}
    (sg/query-root [::m/builds]))

  (event ::m/build-watch-compile! sg/tx)
  (event ::m/build-watch-start! sg/tx)
  (event ::m/build-watch-stop! sg/tx)
  (event ::m/build-compile! sg/tx)
  (event ::m/build-release! sg/tx)
  (event ::m/build-release-debug! sg/tx)

  (render
    (<< [:div.flex-1.overflow-auto.py-2
         [:div.max-w-7xl.mx-auto
          [:div.flex.flex-col
           [:div.grid.grid-cols-1.gap-5.sm:grid-cols-2.md:grid-cols-2.lg:grid-cols-3
            (sg/render-seq builds identity build-card)]]]])))

(defc ui-build-overview [build-ident]
  (bind {::m/keys [build-sources-sorted] :as data}
    (sg/query-ident build-ident
      [::m/build-sources-sorted]))

  (bind state-ref
    (atom {:selected nil}))

  (bind selected
    (sg/watch state-ref [:selected]))

  (bind id->src
    (into {} (map (juxt :resource-id identity)) build-sources-sorted))

  (bind id->idx
    (reduce-kv
      (fn [m idx {:keys [resource-id] :as v}]
        (assoc m resource-id idx))
      {}
      build-sources-sorted))

  (event ::highlight [env {:keys [resource-id]}]
    (swap! state-ref assoc :selected resource-id))

  (bind render-source-entry
    (fn [{:keys [resource-id resource-name] :as item}]
      (let [selected? (= resource-id selected)]
        (<< [:div.text-xs
             {:class (when selected? "font-bold")
              :on-mouseenter {:e ::highlight :resource-id resource-id}}
             resource-name]))))

  (render
    (<< [:div.p-2
         [:div.py-2.text-xl (count build-sources-sorted) " Namespaces used in build"]
         [:div.flex
          [:div.flex-1 "left"]
          [:div
           (sg/render-seq build-sources-sorted nil render-source-entry)]
          [:div.flex-1 "right"]]])))

(defc ui-build-runtimes [build-ident]
  (bind {::m/keys [build-runtimes] :as data}
    (sg/query-ident build-ident
      [::m/build-runtimes]))

  (render
    (let [runtime-count (count build-runtimes)]
      (<< [:div.px-2
           [:div.pt-2
            (condp = runtime-count
              0 "No connected runtimes."
              1 "1 connected runtime:"
              (str runtime-count " connected runtimes:"))]

           (runtimes/ui-runtime-listing build-runtimes)]))))

(defc ui-build-page [build-ident]
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
    (let [{::m/keys [build-id build-target build-status build-worker-active]} data]
      (<< [:div
           [:div.px-2
            [:h1.text-xl.pt-4 (name build-id)]
            [:div " target: " (name build-target)]]
           [:div.p-2 (build-buttons build-id build-worker-active)]]

          (when build-worker-active
            (ui-build-runtimes build-ident))

          (build-status/render-build-status-full build-status)))))