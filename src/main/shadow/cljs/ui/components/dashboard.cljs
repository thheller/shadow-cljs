(ns shadow.cljs.ui.components.dashboard
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.ui.components.builds :as builds]))

(defn card-title [title]
  (<< [:div {:class "px-4 py-5 sm:px-6"}
       [:h3 {:class "text-lg leading-6 font-medium text-gray-900"}
        title]]))

(defc ui-http-servers []
  (bind {::m/keys [http-servers]}
    (sg/query-root
      [{::m/http-servers
        [::m/http-server-id
         ::m/http-url
         ::m/https-url
         ::m/http-config]}]))

  (render
    (<< [:div {:class "shadow bg-white mb-4"}
         (card-title "Active HTTP Servers")
         (sg/keyed-seq http-servers ::m/http-server-id
           (fn [{::m/keys [http-url https-url http-config]}]
             (let [url (or http-url https-url)]
               (<< [:div {:class "border-t border-gray-200 p-4 flex items-center"}
                    [:div.flex-shrink-0
                     [:svg.h-6.w-6.text-gray-400 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
                      [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M5 12h14M5 12a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v4a2 2 0 01-2 2M5 12a2 2 0 00-2 2v4a2 2 0 002 2h14a2 2 0 002-2v-4a2 2 0 00-2-2m-2-4h.01M17 16h.01"}]]]
                    [:div.ml-5.w-0.flex-1
                     [:dl
                      [:dt.text-lg.truncate
                       [:a.font-medium.text-green-700.hover:text-green-900 {:href url :target "_blank"} url]]
                      [:dd
                       [:div.text-sm.font-medium.text-gray-500
                        (let [roots (:roots http-config)]
                          (cond
                            (and (nil? roots) (:proxy-url http-config))
                            (<< "proxy-url: "
                                [:a.text-green-700.hover:text-green-900
                                 {:href (:proxy-url http-config) :target "_blank"}
                                 (:proxy-url http-config)])

                            (and (nil? roots) (:handler http-config))
                            (pr-str (:handler http-config))

                            (nil? roots)
                            ""

                            (= 1 (count roots))
                            (pr-str (first roots))

                            :else
                            (pr-str roots)
                            ))]]]]]))))])))

(defc ui-active-builds []
  (bind {::m/keys [active-builds]}
    (sg/query-root [::m/active-builds]))

  (render
    (<< [:div {:class "bg-white shadow mb-4"}
         (card-title "Active Builds")

         (sg/keyed-seq active-builds identity
           (fn [ident]
             (<< [:div {:class "border-t border-gray-200 "}
                  (builds/build-card ident)])))
         ;(build-status/render-build-status-short build-status)
         ])))

(defc ui-active-runtimes []
  (bind {::m/keys [runtimes-sorted]}
    (sg/query-root
      [::m/runtimes-sorted]))

  (render
    (<< [:div {:class "bg-white shadow mb-4"}
         (card-title "Active Runtimes")
         (runtimes/ui-runtime-list runtimes-sorted)])))

(defn ui-page []
  (<< [:div {:class "flex-1 overflow-auto mt-4 sm:px-3"}
       (ui-active-builds)
       (ui-active-runtimes)
       (ui-http-servers)
       ]))