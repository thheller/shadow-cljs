(ns shadow.cljs.ui.main
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.history :as history]
    [shadow.experiments.grove.worker-engine :as worker-eng]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.inspect :as inspect]
    [shadow.cljs.ui.components.dashboard :as dashboard]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.ui.components.builds :as builds]
    [shadow.cljs.ui.components.eval :as eval]
    [shadow.cljs.ui.components.db-explorer :as db-explorer]
    ))

(defc ui-root* []
  [{::m/keys [current-page]}
   (sg/query-root
     [::m/current-page
      ;; load marker for suspense, ensures that all basic data is loaded
      ::m/init-complete?])

   nav-items
   [{:pages #{:dashboard} :label "Dashboard" :path "/dashboard"}
    {:pages #{:builds :build} :label "Builds" :path "/builds"}
    {:pages #{:repl} :label "Runtimes" :path "/runtimes"}
    {:pages #{:inspect} :label "Inspect" :path "/inspect"}]

   nav-selected
   "inline-block rounded-t px-4 py-2 bg-blue-100 border-b-2 border-blue-200 hover:border-blue-400"
   nav-normal
   "inline-block px-4 py-2"]

  (<< [:div.flex.flex-col.h-full.bg-gray-100
       [:div.bg-white.shadow-md.z-10
        [:div.py-2.px-4
         [:span.font-bold "shadow-cljs"]]
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

(defonce root-el (js/document.getElementById "root"))

(defn ^:dev/after-load start []
  (sg/start ::ui root-el (ui-root)))

(defn init []
  (sg/init ::ui
    {}
    [(worker-eng/init js/SHADOW_WORKER)
     (history/init)])

  (js/setTimeout start 0))