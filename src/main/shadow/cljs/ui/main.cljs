(ns shadow.cljs.ui.main
  (:require
    [fipp.edn :refer (pprint)]
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.history :as history]
    [shadow.experiments.grove.worker-engine :as worker-eng]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.inspect.views :as inspect]
    [shadow.cljs.ui.components.build-status :as build-status]
    ))

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

(defc ui-dashboard-page []
  []
  (<< [:div.flex-1.overflow-auto
       (ui-http-servers)
       (ui-active-runtimes)
       (ui-active-builds)]))

(defc ui-builds-page []
  [{::m/keys [builds]}
   (sg/query-root
     [{::m/builds
       [::m/build-id]}])]
  (<< [:div.flex-1.overflow-auto
       [:div.p-2
        (sg/render-seq builds ::m/build-id
          (fn [{::m/keys [build-id] :as item}]
            (<< [:div.py-1
                 [:a.font-bold {:href (str "/build/" (name build-id))} (name build-id)]])))
        ]]))

(defc ui-build-page []
  [{::m/keys [current-build] :as data}
   (sg/query-root
     [{::m/current-build
       [:db/ident
        ::m/build-id
        ::m/build-target
        ::m/build-worker-active
        ::m/build-status]}])

   ::m/build-watch-compile! sg/tx
   ::m/build-watch-start! sg/tx
   ::m/build-watch-stop! sg/tx
   ::m/build-compile! sg/tx
   ::m/build-release! sg/tx]

  (let [{::m/keys [build-id build-target build-status build-worker-active]} current-build]
    (<< [:div.flex-1.overflow-auto
         [:h1.text-xl.px-2.py-4 (name build-id) " - " (name build-target)]

         [:div
          [:div.p-2.text-lg.font-bold "Actions"]
          [:div.p-2
           (if build-worker-active
             (<< [:button.py-2.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
                  {:on-click [::m/build-watch-compile! build-id]}
                  "force compile"]
                 [:button.ml-2.py-2.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
                  {:on-click [::m/build-watch-stop! build-id]}
                  "stop watch"])

             (<< [:button.py-2.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
                  {:on-click [::m/build-watch-start! build-id]}
                  "start watch"]
                 [:button.ml-2.py-2.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
                  {:on-click [::m/build-compile! build-id]}
                  "compile"]
                 [:button.ml-2.py-2.px-4.bg-blue-200.hover:bg-blue-300.rounded.shadow
                  {:on-click [::m/build-release! build-id]}
                  "release"]))]]

         [:div.p-2
          [:div.text-lg "Build Status"]
          (build-status/render-build-status build-status)]])))

(defc ui-root* []
  [{::m/keys [current-page]}
   (sg/query-root
     [::m/current-page
      ;; load marker for suspense, ensures that all basic data is loaded
      ::m/init-complete])

   nav-items
   [{:pages #{:dashboard} :label "Dashboard" :path "/dashboard"}
    {:pages #{:builds :build} :label "Builds" :path "/builds"}
    {:pages #{:inspect} :label "Inspect" :path "/inspect"}]

   nav-selected
   "inline-block rounded-t px-4 py-2 bg-blue-100 border-b-2 border-blue-200 hover:border-blue-400"
   nav-normal
   "inline-block px-4 py-2"]

  (<< [:div.flex.flex-col.h-full.bg-gray-100
       [:div.bg-white.shadow-md.z-10
        [:div.py-2.px-4
         [:span.font-bold "shadow-cljs"]
         ;; FIXME: show websocket connection status
         ;; and maybe some indicator how many build are running and so on
         ]
        [:div
         (sg/render-seq nav-items :path
           (fn [{:keys [pages label path]}]
             (<< [:a {:class (if (contains? pages current-page) nav-selected nav-normal)
                      :href path} label])))]]

       (sg/suspense
         {:fallback "Loading ..."
          :timeout 500}
         (case current-page
           :inspect
           (inspect/ui-page)

           :builds
           (ui-builds-page)

           :build
           (ui-build-page)

           :dashboard
           (ui-dashboard-page)

           "Unknown Page"))]))

(defc ui-root []
  []
  (sg/suspense
    {:timeout 2000
     :fallback
     (<< [:div.inset-0.text-center.py-16
          [:div.text-2xl.font-bold "shadow-cljs"]
          [:div "Loading ..."]])}
    (ui-root*)))

;; FIXME: ui-env should NEVER be accessed directly by anything
;; the only point this is here is for hot-reload purposes
;; ui-env should be immutable once mounted
;; maybe this can be done entirely without the user having to configure it
(defonce ui-env {})
(defonce root-el (js/document.getElementById "root"))

(defn ^:dev/after-load start []
  (sg/start ui-env root-el (ui-root)))

(defn init []
  (set! ui-env (-> ui-env
                   (sg/init ::ui)
                   (worker-eng/init (js/Worker. "/js/worker.js"))
                   (history/init)))

  (js/setTimeout start 0))