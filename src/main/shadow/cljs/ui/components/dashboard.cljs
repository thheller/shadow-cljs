(ns shadow.cljs.ui.components.dashboard
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.ui.components.builds :as builds]))

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
         [:h2.pl-1.text-lg.leading-6.font-medium.text-gray-900 "HTTP Servers"]
         [:div.mt-2.grid.grid-cols-1.gap-5
          (sg/render-seq http-servers ::m/http-server-id
            (fn [{::m/keys [http-url https-url http-config]}]
              (let [url (or http-url https-url)
                    display-name (:display-name http-config)]
                (<< [:div.bg-white.shadow
                     [:div.p-5
                      [:div.flex.items-center
                       [:div.flex-shrink-0
                        [:svg.h-6.w-6.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
                         [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01"}]]]
                       [:div.ml-5.w-0.flex-1
                        [:dl
                         [:dt.text-lg.font-medium.text-gray-900.truncate url]
                         [:dd
                          [:div.text-sm.font-medium.text-gray-500 (pr-str (first (:roots http-config)))]]]]]]
                     [:div.bg-gray-50.px-5.py-3
                      [:div.text-sm
                       [:a.font-medium.text-green-700.hover:text-green-900 {:href url :target "_blank"} "Open"]]]]))))]])))

(defc ui-active-builds []
  (bind {::m/keys [active-builds]}
    (sg/query-root [::m/active-builds]))

  (render
    (<< [:div
         [:h2.pl-1.text-lg.leading-6.font-medium.text-gray-900 "Builds"]
         [:div.mt-2.grid.grid-cols-1.gap-5
          (sg/render-seq active-builds identity builds/build-card)
          ;(build-status/render-build-status-short build-status)
          ]])))

(defc ui-active-runtimes []
  (bind {::m/keys [runtimes-sorted]}
    (sg/query-root
      [::m/runtimes-sorted]))

  (render
    (<< [:div.mt-8
         [:h2.pl-1.text-lg.leading-6.font-medium.text-gray-900
          "Runtimes"]
         [:div.-mx-2
          (runtimes/ui-runtime-list runtimes-sorted)]])))

(defn ui-page []
  (<< [:div.flex-1.overflow-auto.py-2.sm:px-2
       [:div.flex.flex-col
        [:div.align-middle.min-w-full
         (ui-active-builds)
         (ui-active-runtimes)
         (ui-http-servers)
         ]]]))