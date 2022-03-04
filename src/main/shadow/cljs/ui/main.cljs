(ns shadow.cljs.ui.main
  {:dev/always true}
  (:require
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove.history :as history]
    [shadow.experiments.grove.keyboard :as keyboard]
    [shadow.experiments.grove.local :as local-eng]
    [shadow.experiments.grove.events :as ev]
    [shadow.experiments.grove.transit :as transit]
    [shadow.experiments.grove.http-fx :as http-fx]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.db.env :as env]
    [shadow.cljs.ui.db.relay-ws :as relay-ws]
    [shadow.cljs.ui.db.generic]
    [shadow.cljs.ui.db.builds]
    [shadow.cljs.ui.db.inspect]
    [shadow.cljs.ui.db.explorer]
    [shadow.cljs.ui.components.inspect :as inspect]
    [shadow.cljs.ui.components.dashboard :as dashboard]
    [shadow.cljs.ui.components.runtimes :as runtimes]
    [shadow.cljs.ui.components.builds :as builds]
    [shadow.cljs.ui.components.build :as build]
    [shadow.cljs.ui.components.eval :as eval]
    [shadow.cljs.ui.components.db-explorer :as db-explorer]
    [shadow.cljs.ui.components.common :as common]
    ))

(defc ui-error [err-ident]
  (bind {:keys [text]}
    (sg/query-ident err-ident
      [:text]))

  (event ::keyboard/escape [env _ e]
    (sg/run-tx env {:e ::m/dismiss-error! :ident err-ident}))

  (render
    (<< [:div.w-full.h-full.bg-white.shadow.border.flex.flex-col {::keyboard/listen true}
         [:div.flex
          [:div.text-red-700.p-2.font-bold "An error occured:"]
          [:div.flex-1]
          [:div.text-right.cursor-pointer.font-bold.p-2
           {:on-click {:e ::m/dismiss-error! :ident err-ident}}
           common/icon-close]]
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
     {:pages #{:inspect} :label "Inspect Stream" :path "/inspect"}
     {:pages #{:inspect-latest} :label "Inspect Latest" :path "/inspect-latest"}])

  (render
    (let [nav-selected
          "inline-block rounded-t px-4 py-2 bg-blue-100 border-b-2 border-blue-200 hover:border-blue-400"

          nav-normal
          "inline-block px-4 py-2"]

      (<< [:div.h-full.w-full.flex.flex-col.bg-gray-100.items-stretch
           (when-not relay-ws-connected
             (<< [:div.p-4.bg-red-700.text-white.text-lg.font-bold "UI WebSocket not connected! Reload page to reconnect."]))

           [:div.bg-white.shadow-md.z-10
            #_[:div.py-2.px-4 [:span.font-bold "shadow-cljs"]]
            [:div
             (sg/simple-seq nav-items
               (fn [{:keys [pages label path]}]
                 (<< [:a
                      {:class (if (contains? pages (:id current-page)) nav-selected nav-normal)
                       :ui/href path}
                      label])))]]

           (sg/suspense
             {:fallback "Loading ..."
              :timeout 500}
             (case (:id current-page)
               (:inspect :inspect-latest :explore-runtime)
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

(defn start []
  (sg/render env/rt-ref root-el (ui-root)))

;; macro magic requires this ns always being recompiled
;; not yet possible to hide this, maybe a build-hook would be better than a macro?
;; experimenting with use metadata to configure app, might also be more useful
;; for documentation purposes or tooling.
(defn register-events! []
  (ev/register-events! env/rt-ref))

(defn ^:dev/after-load reload []
  (register-events!)
  (start))

(defonce server-token
  (-> (js/document.querySelector "meta[name=\"shadow-remote-token\"]")
      (.-content)))

(defn init []
  ;; needs to be called before start since otherwise we can't process events triggered by any of these
  (register-events!)

  (transit/init! env/rt-ref)

  (local-eng/init! env/rt-ref)

  (when ^boolean js/goog.DEBUG
    (swap! env/rt-ref assoc :shadow.experiments.grove.events/tx-reporter
      (fn [report]
        (js/console.log (-> report :event :e) report))))

  (history/init! env/rt-ref
    {:start-token "/dashboard"
     :root-el root-el})

  (ev/reg-fx env/rt-ref :graph-api
    (http-fx/make-handler
      {:on-error {:e ::m/request-error!}
       :base-url "/api/graph"
       :request-format :transit}))

  (relay-ws/init env/rt-ref server-token
    (fn []
      (relay-ws/cast! @env/rt-ref
        {:op ::m/load-ui-options
         :to 1 ;; FIXME: don't blindly assume CLJ runtime is 1
         })

      ;; builds starting, stopping
      (relay-ws/cast! @env/rt-ref
        {:op ::m/subscribe
         :to 1 ;; FIXME: don't blindly assume CLJ runtime is 1
         ::m/topic ::m/supervisor})

      ;; build progress, errors, success
      (relay-ws/cast! @env/rt-ref
        {:op ::m/subscribe
         :to 1 ;; FIXME: don't blindly assume CLJ runtime is 1
         ::m/topic ::m/build-status-update})))

  (sg/run-tx! env/rt-ref {:e ::m/init!})

  (js/setTimeout start 0))