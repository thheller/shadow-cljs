(ns shadow.cljs.ui.components.dashboard
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.build-status :as build-status]))

(defc ui-http-servers []
  [{::m/keys [http-servers]}
   (sg/query-root
     [{::m/http-servers
       [::m/http-server-id
        ::m/http-url
        ::m/https-url
        ::m/http-config]}])]

  (<< [:div.m-4.rounded.border.shadow.bg-white
       [:div.p-2.font-bold.border-b "HTTP Servers"]
       [:ol.pl-6.pt-1.list-disc
        (sg/render-seq http-servers ::m/http-server-id
          (fn [{::m/keys [http-url https-url http-config]}]
            (let [url (or http-url https-url)]
              (<< [:li.pb-1
                   [:a.font-bold {:href url :target "_blank"} url]
                   " - "
                   (pr-str (:roots http-config))]))))]]))

(defc ui-active-build [ident]
  [{::m/keys [build-status build-id build-target] :as data}
   (sg/query-ident ident
     [::m/build-id
      ::m/build-target
      ::m/build-status
      ::m/build-config-raw])]

  (<< [:div.p-2
       [:div.text-xl
        [:a {:href (str "/build/" (name build-id))}
         (name build-id) " - " (name build-target)]]

       (build-status/render-build-status build-status)]))

(defc ui-active-builds []
  [{::m/keys [active-builds]}
   (sg/query-root [::m/active-builds])]

  (<< [:div.m-4.rounded.border.shadow.bg-white
       [:div.p-2.font-bold.border-b "Active Builds"]
       (sg/render-seq active-builds identity ui-active-build)
       ]))

(defc ui-active-runtimes []
  [{::m/keys [runtimes-sorted]}
   (sg/query-root
     [{::m/runtimes-sorted
       [:rid
        :runtime-info
        :supported-ops]}])]

  (<< [:div.m-4.rounded.border.shadow.bg-white
       [:div.p-2.font-bold.border-b "Active Runtimes"]
       [:ol.pl-6.pt-1.list-disc
        (sg/render-seq runtimes-sorted :rid
          (fn [runtime]
            (<< [:li (pr-str runtime)])))]]))

(defn ui-page []
  (<< [:div.flex-1.overflow-auto
       (ui-http-servers)
       #_ (ui-active-runtimes)
       (ui-active-builds)]))
