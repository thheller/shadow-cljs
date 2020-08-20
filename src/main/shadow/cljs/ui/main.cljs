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
    [shadow.cljs.ui.components.eval :as eval]
    [shadow.cljs.ui.components.db-explorer :as db-explorer]
    [clojure.string :as str]
    [shadow.cljs.ui.components.common :as common]))

(defc ui-error [err-ident]
  (bind {:keys [text]}
    (sg/query-ident err-ident
      [:text]))

  (hook
    (keyboard/listen
      {"escape"
       (fn [env e]
         (sg/run-tx env [::m/dismiss-error! err-ident]))}))

  (event ::m/dismiss-error! sg/tx)

  (render
    (<< [:div.w-full.h-full.bg-white.shadow.border.flex.flex-col
         [:div.flex
          [:div.text-red-700.p-2.font-bold "An error occured:"]
          [:div.flex-1]
          [:div.text-right.cursor-pointer.font-bold.p-2
           {:on-click [::m/dismiss-error! err-ident]}
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
     {:pages #{:inspect} :label "Inspect" :path "/inspect"}])

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
             (sg/render-seq nav-items nil
               (fn [{:keys [pages label path]}]
                 (<< [:a
                      {:class (if (contains? pages (:id current-page)) nav-selected nav-normal)
                       :href path}
                      label])))]]

           (sg/suspense
             {:fallback "Loading ..."
              :timeout 500}
             (case (:id current-page)
               :inspect
               (inspect/ui-page)

               :builds
               (builds/ui-builds-page)

               :build
               (builds/ui-build-page (:ident current-page))

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
     (history/init)
     (keyboard/init)])

  (js/setTimeout start 0))