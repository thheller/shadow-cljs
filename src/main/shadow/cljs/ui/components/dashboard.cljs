(ns shadow.cljs.ui.components.dashboard
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.ui.components.runtimes :as runtimes]))

(defc ui-http-servers []
  (bind {::m/keys [http-servers]}
    (sg/query-root
      [{::m/http-servers
        [::m/http-server-id
         ::m/http-url
         ::m/https-url
         ::m/http-config]}]))

  (render
    (<< [:div.mt-8
         [:div.max-w-7xl.mx-auto
          [:h2.text-lg.leading-6.font-medium.text-gray-900 "HTTP Servers"]
          [:div.mt-2.grid.grid-cols-1.gap-5.sm:grid-cols-2.lg:grid-cols-3
           (sg/render-seq http-servers ::m/http-server-id
             (fn [{::m/keys [http-url https-url http-config]}]
               (let [url (-> (or http-url https-url)
                           (clojure.string/split  #"//")
                           (second))
                     display-name (:display-name http-config)]
                 (<< [:div.bg-white.overflow-hidden.shadow.rounded-lg
                      [:div.p-5
                       [:div.flex.items-center
                        [:div.flex-shrink-0
                         [:svg.h-6.w-6.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
                          [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01"}]]]
                        [:div.ml-5.w-0.flex-1
                         [:dl
                          [:dt.text-sm.font-medium.text-gray-500.truncate (pr-str (first (:roots http-config)))]
                          [:dd
                           [:div.text-lg.font-medium.text-gray-900 url]]]]]]
                      [:div.bg-gray-50.px-5.py-3
                       [:div.text-sm
                        [:a.font-medium.text-green-700.hover:text-green-900 {:href url :target "_blank"} "View"]]]]))))]]])))

(defc ui-active-build [ident]

  (event ::m/build-watch-stop! sg/tx)
  (event ::m/build-watch-compile! sg/tx)

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
      (<< [:div.bg-white.overflow-hidden.shadow.rounded-lg
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
                [:div.text-lg.font-medium.text-gray-900 (name build-id)]]]]]]
           [:div
            [:div.-mt-px.flex.divide-x.divide-gray-200
             [:div.w-0.flex-1.flex
              [:button.relative.w-0.flex-1.inline-flex.items-center.justify-center.py-4.text-sm.text-gray-700.font-medium.border.border-transparent.hover:text-gray-500.focus:outline-none
               {:on-click {:e ::m/build-watch-compile! :build-id build-id}}
               [:svg.w-5.h-5.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
                [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M11.933 12.8a1 1 0 000-1.6L6.6 7.2A1 1 0 005 8v8a1 1 0 001.6.8l5.333-4zM19.933 12.8a1 1 0 000-1.6l-5.333-4A1 1 0 0013 8v8a1 1 0 001.6.8l5.333-4z"}]]
               [:span.ml-2 "Recompile"]]]
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
               [:span.ml-2 "View"]]]]]]))))

(defc ui-active-builds []
  (bind {::m/keys [active-builds]}
    (sg/query-root [::m/active-builds]))

  (render
    (<< [:div.mt-8
         [:div.max-w-7xl.mx-auto
          [:h2.text-lg.leading-6.font-medium.text-gray-900 "Builds"]
          [:div.mt-2.grid.grid-cols-1.gap-5.sm:grid-cols-2.lg:grid-cols-3
           (sg/render-seq active-builds identity ui-active-build)]]])))

(defc ui-active-runtimes []
  (bind {::m/keys [runtimes-sorted]}
    (sg/query-root
      [{::m/runtimes-sorted
        [:runtime-id
         :runtime-info
         :supported-ops]}]))

  (render
    (<< [:div.mt-8
         [:div.max-w-7xl.mx-auto
          [:h2.text-lg.leading-6.font-medium.text-gray-900 "Runtimes"]
          (runtimes/ui-page)]])))

(defn ui-page []
  (<< [:div.flex-1.overflow-auto.py-2
       [:div.max-w-7xl.mx-auto
        [:div.flex.flex-col
         [:div.align-middle.min-w-full.overflow-x-auto.overflow-hidden
          (ui-active-builds)
          (ui-active-runtimes)
          (ui-http-servers)]]]]))