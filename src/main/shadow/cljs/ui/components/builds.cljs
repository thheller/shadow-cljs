(ns shadow.cljs.ui.components.builds
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.model :as m]))

(defn build-buttons [build-id build-worker-active]
  (if build-worker-active
    (<< [:div
         [:button.inline-flex.items-center.px-2.5.py-1.5.text-xs.font-medium.rounded.text-gray-700.bg-gray-50.hover:bg-gray-300.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-gray-500
          {:on-click {:e ::m/build-watch-stop! :build-id build-id}
           :type "button"}
          "Stop"]
         [:button.ml-2.inline-flex.items-center.px-2.5.py-1.5.text-xs.font-medium.rounded.text-gray-700.bg-gray-50.hover:bg-gray-300.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-gray-500
          {:on-click {:e ::m/build-watch-compile! :build-id build-id}
           :type "button"}
          "Force compile"]])
    (<< [:div
         [:button.inline-flex.items-center.px-2.5.py-1.5.text-xs.font-medium.rounded.text-gray-700.bg-gray-50.hover:bg-gray-300.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-gray-500
          {:on-click {:e ::m/build-watch-start! :build-id build-id}
           :type "button"}
          "Start"]
         [:button.ml-2.inline-flex.items-center.px-2.5.py-1.5.text-xs.font-medium.rounded.text-gray-700.bg-gray-50.hover:bg-gray-300.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-gray-500
          {:on-click {:e ::m/build-compile! :build-id build-id}
           :type "button"}
          "Compile"]
         [:button.ml-2.inline-flex.items-center.px-2.5.py-1.5.text-xs.font-medium.rounded.text-gray-700.bg-gray-50.hover:bg-gray-300.focus:outline-none.focus:ring-2.focus:ring-offset-2.focus:ring-gray-500
          {:on-click {:e ::m/build-release! :build-id build-id}
           :type "button"}
          "Release"]])))

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
           [:a {:href (str "/build/" (name build-id))}
            [:div.p-5
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
              [:a.ml-5.w-0.flex-1
               [:dl
                [:dt.text-lg.font-medium.text-gray-900 (name build-id)]
                [:dd
                 [:span.text-sm.font-medium.text-gray-500.truncate (name build-target)]]]]]]]
           [:div.bg-gray-50.px-3.py-3
            (build-buttons build-id build-worker-active)]]))))

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
    (<< [:div.flex-1.overflow-auto.py-2.sm:px-2
         [:div.max-w-7xl
          [:div.flex.flex-col
           [:div.grid.grid-cols-1.gap-5.md:grid-cols-2.lg:grid-cols-3
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