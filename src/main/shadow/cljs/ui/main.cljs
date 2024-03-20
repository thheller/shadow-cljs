(ns shadow.cljs.ui.main
  {:dev/always true
   :shadow.css/include ["shadow/cljs/ui/main.css"]}
  (:require
    [shadow.grove :as sg :refer (<< defc)]
    [shadow.css :refer (css)]
    [shadow.grove.history :as history]
    [shadow.grove.keyboard :as keyboard]
    [shadow.grove.events :as ev]
    [shadow.grove.transit :as transit]
    [shadow.grove.http-fx :as http-fx]
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
    [shadow.cljs.ui.components.common :as common]
    ))

(defc ui-error [err-ident]
  (bind {:keys [text]}
    (sg/query-ident err-ident
      [:text]))

  (event ::keyboard/escape [env _ e]
    (sg/run-tx env {:e ::m/dismiss-error! :ident err-ident}))

  (render
    (<< [:div {::keyboard/listen true
               :class (css :w-full :h-full :bg-white :shadow :border :flex :flex-col)}
         [:div {:class (css :flex)}
          [:div {:class (css :text-red-700 :p-2 :font-bold)} "An error occured:"]
          [:div {:class (css :flex-1)}]
          [:div
           {:class (css :text-right :cursor-pointer :font-bold :p-2)
            :on-click {:e ::m/dismiss-error! :ident err-ident}}
           common/icon-close]]
         [:pre {:class (css :overflow-auto :p-2)} text]])))

(defc ui-errors []
  (bind {::m/keys [errors]}
    (sg/query-root [::m/errors]))

  (render
    (when (seq errors)
      (<< [:div
           {:class (css :fixed :inset-0 :z-50 :w-full :h-full :flex :flex-col
                     {:background-color "rgba(0,0,0,0.4)"})}
           [:div {:class (css :flex-1 :p-8 :overflow-hidden)}
            (ui-error (first errors))
            ]]))))

(defc ui-settings-drawer []
  (bind {::m/keys [show-settings preferred-display-type] :or {preferred-display-type :browse}}
    (sg/query-root [::m/show-settings ::m/preferred-display-type]))

  (bind display-options
    [{:val :browse :label "BROWSER"}
     {:val :pprint :label "PPRINT"}
     {:val :edn :label "EDN"}])

  (render
    (when show-settings
      (<< [:div {:class (css :absolute :bg-white :shadow-2xl {:border-left "2px solid #e5e7eb" :top "0px" :right "0px" :bottom "0px" :z-index 10})}

           [:div {:class (css :px-4 {:min-width "400px"})}
            [:div {:class (css :py-4 :cursor-pointer {:float "right"}) :on-click ::m/close-settings!} common/icon-close]
            [:div {:class (css :text-2xl :py-4)} "UI Settings"]

            [:div {:class (css :text-lg)} "Preferred Display Type"]

            [:p {:class (css :text-xs)} "Only affects new incoming Inspect Values"]

            (sg/simple-seq display-options
              (fn [{:keys [label val]}]
                (<< [:div
                     {:class [inspect/$button-base (if (= preferred-display-type val) inspect/$button-selected inspect/$button) (css :my-2)]
                      :on-click {:e ::m/switch-preferred-display-type! :display-type val}}
                     label])))

            [:div
             {:class [inspect/$button-base inspect/$button (css :cursor-pointer :inline-block :mt-8)]
              :on-click ::m/close-settings!}
             "Close"]]]

          ))))

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
    (let [$nav-selected
          (css :inline-block :rounded-t :px-4 :py-2 :bg-blue-100 :border-b-2 :border-blue-200 [:hover :border-blue-400])

          $nav-normal
          (css :cursor-pointer :inline-block :px-4 :py-2)]

      (<< [:div {:class (css #_["& *" :border-gray-100] :h-full :w-full :flex :flex-col :bg-gray-100 :items-stretch)}
           (when-not relay-ws-connected
             (<< [:div {:class (css :p-4 :bg-red-700 :text-white :text-lg :font-bold)}
                  "UI WebSocket not connected! Reload page to reconnect."]))

           (sg/portal (ui-settings-drawer))

           [:div {:class (css :flex :bg-white :shadow-md :z-10)}
            #_[:div.py-2.px-4 [:span.font-bold "shadow-cljs"]]
            (sg/simple-seq nav-items
              (fn [{:keys [pages label path]}]
                (<< [:a
                     {:class (if (contains? pages (:id current-page)) $nav-selected $nav-normal)
                      :ui/href path}
                     label])))
            [:div {:class (css :flex-1)}]
            [:div {:class $nav-normal :on-click ::m/open-settings! :title "Open Settings"} common/icon-cog]]

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

               "Unknown Page"))]

          ;; FIXME: portal this?
          (ui-errors)))))

(defn ui-root []
  (sg/suspense
    {:timeout 2000
     :fallback
     (<< [:div {:class (css :inset-0 :text-center :py-16)}
          [:div {:class (css :text-2xl :font-bold)} "shadow-cljs"]
          [:div "Loading ..."]])}
    (ui-root*)))

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

  (when ^boolean js/goog.DEBUG
    (swap! env/rt-ref assoc :shadow.grove.runtime/tx-reporter
      (fn [report]
        (let [e (-> report :event :e)]
          (case e
            ::m/relay-ws
            (js/console.log "[WS]" (-> report :event :msg :op) (-> report :event :msg) report)
            (js/console.log e report))))))

  (history/init! env/rt-ref
    {:start-token "/dashboard"
     :root-el root-el})

  (sg/reg-fx env/rt-ref :http-api
    (http-fx/make-handler
      {:on-error {:e ::m/request-error!}
       :base-url "/api"
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