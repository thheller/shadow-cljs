(ns shadow.cljs.ui.main
  (:require
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.history :as history]
    [shadow.experiments.grove.keyboard :as keyboard]
    [shadow.experiments.grove.worker-engine :as worker-eng]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.inspect :as inspect]
    [shadow.cljs.ui.components.dashboard :as dashboard]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.ui.components.builds :as builds]
    [shadow.cljs.ui.components.build :as build]
    [shadow.cljs.ui.components.eval :as eval]
    [shadow.cljs.ui.components.db-explorer :as db-explorer]
    [shadow.cljs.ui.components.common :as common]))

(defc ui-error [err-ident]
  (bind {:keys [text]}
    (sg/query-ident err-ident
      [:text]))

  (event ::m/dismiss-error! sg/tx)

  (event ::keyboard/escape [env _ e]
    (sg/run-tx env {:e ::m/dismiss-error! :ident err-ident}))

  (render
    (<< [:div.w-full.h-full.bg-white.shadow.border.flex.flex-col {::keyboard/listen true}
         [:div.flex
          [:div.text-red-700.p-2.font-bold "An error occured:"]
          [:div.flex-1]
          [:div.text-right.cursor-pointer.font-bold.p-2
           {:on-click {:e ::m/dismiss-error! :ident err-ident}}
           common/svg-close]]
         [:pre.overflow-auto.p-2.overflow-auto text]])))

(defc ui-errors []
  (bind {::m/keys [errors]}
    (sg/query-root [::m/errors]))

  (render
    (when (seq errors)
      (<< [:div.fixed.inset-0.z-50.w-full.h-full.flex.flex-col
           {:style "background-color: rgba(0,0,0,0.4)"}
           [:div.flex-1.p-8.overflow-hidden
            (ui-error (first errors))
            ]]))))

#_(defc ui-error [err-ident]
  (bind {:keys [text]}
    (sg/query-ident err-ident
      [:text]))

  (event ::m/dismiss-error! sg/tx)

  (event ::keyboard/escape [env _ e]
    (sg/run-tx env {:e ::m/dismiss-error! :ident err-ident}))

  (render
    (<< [:div.flex.items-center.justify-between.flex-wrap
         [:div.w-0.flex-1.flex.items-center
          [:span.flex.p-2.rounded-lg.bg-yellow-400
           [:svg.h-6.w-6.text-white {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor"}
            [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"}]]]
          [:p.ml-3.font-medium.text-yellow-500.truncate
           [:span.md:hidden.truncate text]
           [:span.hidden.md:inline text]]]
         [:div.order-3.mt-2.flex-shrink-0.w-full.sm:order-2.sm:mt-0.sm:w-auto
          [:a.flex.items-center.justify-center.px-4.py-2.border.border-transparent.rounded-md.shadow-sm.text-sm.font-medium.text-white.bg-yellow-400.hover:bg-yellow-500
           {:href "#"}
           (str "Details " \u2192)]]
         [:div.order-2.flex-shrink-0.sm:order-3.sm:ml-2
          [:button.flex.p-2.rounded-md.hover:bg-yellow-100.focus:outline-none.focus:ring-2.focus:ring-white
           {:on-click {:e ::m/dismiss-error! :ident err-ident}
            :type "button"}
           [:span.sr-only "Dismiss"]
           [:svg.h-6.w-6.text-yellow-500 {:xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :stroke "currentColor" :aria-hidden "true"}
            [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d "M6 18L18 6M6 6l12 12"}]]]]])))

#_(defc ui-errors []
  (bind {::m/keys [errors]}
    (sg/query-root [::m/errors]))

  (render
    (when (seq errors)
      (<< [:div.fixed.bottom-0.inset-x-0.pb-2.sm:pb-5
           [:div.max-w-full.mx-1.px-2.sm:px-6.lg:px-8
            [:div.p-2.rounded-lg.bg-yellow-50.border.border-yellow-200.shadow-lg.sm:p-3
             (ui-error (first errors))]]]))))

(defc ui-root* []
  (bind {::m/keys [current-page relay-ws-connected] :as data}
    (sg/query-root
      [::m/current-page
       ;; load marker for suspense, ensures that all basic data is loaded
       ::m/init-complete?
       ::m/relay-ws-connected]))

  (bind nav-items
    [{:pages #{:dashboard} :label "Dashboard" :path "/dashboard"}
     {:pages #{:builds :build} :label "Builds" :path "/builds"}
     {:pages #{:runtimes} :label "Runtimes" :path "/runtimes"}
     {:pages #{:inspect} :label "Inspect Stream" :path "/inspect"}
     {:pages #{:inspect-latest} :label "Inspect Latest" :path "/inspect-latest"}])

  (render
    (let [nav-selected
          "inline-block rounded-t px-4 py-2 bg-blue-100 border-b-2 border-blue-200 hover:border-blue-400"

          nav-normal
          "inline-block px-4 py-2"]

      (<< [:div.flex.flex-col.h-full.bg-gray-100
           (when-not relay-ws-connected
             (<< [:div.p-4.bg-red-700.text-white.text-lg.font-bold "UI WebSocket not connected! Reload page to reconnect."]))

           [:div.bg-white.shadow-md.z-10
            #_[:div.py-2.px-4 [:span.font-bold "shadow-cljs"]]
            [:div
             (sg/simple-seq nav-items
               (fn [{:keys [pages label path]}]
                 (<< [:a
                      {:class (if (contains? pages (:id current-page)) nav-selected nav-normal)
                       :href path}
                      label])))]]

           (sg/suspense
             {:fallback "Loading ..."
              :timeout 500}
             (case (:id current-page)
               (:inspect :inspect-latest)
               (inspect/ui-page)

               :builds
               (builds/ui-builds-page)

               :build+status
               (build/ui-page-status (:ident current-page))

               :build+runtimes
               (build/ui-page-runtimes (:ident current-page))

               :dashboard
               (dashboard/ui-page)

               :runtimes
               (runtimes/ui-page)

               :repl
               (eval/ui-repl-page (:ident current-page))

               :db-explorer
               (db-explorer/ui-page (:ident current-page))

               "Unknown Page"))]

          ;; FIXME: portal this?
          (ui-errors)))))

(defc ui-root []
  (render
    (<< (sg/suspense
          {:timeout 2000
           :fallback
           (<< [:div.inset-0.text-center.py-16
                [:div.text-2xl.font-bold "shadow-cljs"]
                [:div "Loading ..."]])}
          (ui-root*)))))

(defonce root-el (js/document.getElementById "root"))

(defn ^:dev/after-load start []
  (sg/start ::ui root-el (ui-root)))

(defn init []
  (sg/init ::ui
    {}
    [(worker-eng/init js/SHADOW_WORKER)
     (history/init)])

  (js/setTimeout start 0))