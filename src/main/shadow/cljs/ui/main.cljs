(ns shadow.cljs.ui.main
  {:dev/always true
   :shadow.css/include ["shadow/cljs/ui/main.css"]}
  (:require
    [clojure.core.protocols :as cp]
    [shadow.grove :as sg :refer (<< defc)]
    [shadow.css :refer (css)]
    [shadow.grove.history :as history]
    [shadow.grove.keyboard :as keyboard]
    [shadow.grove.events :as ev]
    [shadow.grove.transit :as transit]
    [shadow.grove.http-fx :as http-fx]
    [shadow.cljs :as-alias m]
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
    [shadow.cljs.ui.components.common :as common]
    [shadow.cljs.ui.components.repl :as repl]
    ))

(defrecord X [a])

(defc ui-error [error-id]
  (bind {:keys [text]}
    (sg/kv-lookup ::m/error error-id))

  (event ::keyboard/escape [env _ e]
    (sg/run-tx env {:e ::m/dismiss-error! :ident error-id}))

  (render
    (<< [:div {::keyboard/listen true
               :class (css :w-full :h-full :bg-white :shadow :border :flex :flex-col)}
         [:div {:class (css :flex)}
          [:div {:class (css :text-red-700 :p-2 :font-bold)} "An error occured:"]
          [:div {:class (css :flex-1)}]
          [:div
           {:class (css :text-right :cursor-pointer :font-bold :p-2)
            :on-click {:e ::m/dismiss-error! :ident error-id}}
           common/icon-close]]
         [:pre {:class (css :overflow-auto :p-2)} text]])))

(defc ui-errors []
  (bind errors
    (sg/kv-lookup ::m/ui ::m/errors))

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
    (sg/kv-lookup ::m/ui))

  (bind display-options
    [{:val :browse :label "BROWSER"}
     {:val :pprint :label "PPRINT"}
     {:val :edn-pretty :label "EDN (pretty)"}
     {:val :edn :label "EDN (raw)"}])

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
  (bind {::m/keys [init-complete? current-page relay-ws-connected]}
    (sg/kv-lookup ::m/ui))

  (hook
    (when-not init-complete?
      (sg/suspend!)))

  (bind nav-items
    [{:pages #{:dashboard} :label "Dashboard" :path "/dashboard"}
     {:pages #{:builds :build} :label "Builds" :path "/builds"}
     {:pages #{:repl} :label "REPL" :path "/repl"}
     {:pages #{:runtimes} :label "Runtimes" :path "/runtimes"}
     {:pages #{:inspect} :label "Inspect" :path "/inspect"}
     {:pages #{:inspect-latest} :label "Inspect Latest" :path "/inspect-latest"}])

  (render
    (let [$nav-selected
          (css :inline-block :px-3 :py-1 :bg-blue-100 :border-b-2 :border-blue-200 [:hover :border-blue-400])

          $nav-normal
          (css :cursor-pointer :inline-block :px-3 :py-1)]

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

               :build
               (build/ui-page (:build-id current-page) (:tab current-page))

               :dashboard
               (dashboard/ui-page)

               :runtimes
               (runtimes/ui-page)

               :repl
               (repl/ui-page)

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

(defonce root-el
  (js/document.getElementById "root"))

(def rt-ref
  (sg/get-runtime ::m/ui))

(defn start []
  (sg/render rt-ref root-el (ui-root)))

;; macro magic requires this ns always being recompiled
;; not yet possible to hide this, maybe a build-hook would be better than a macro?
;; experimenting with use metadata to configure app, might also be more useful
;; for documentation purposes or tooling.
(defn register-events! []
  (ev/register-events! rt-ref))

(defn ^:dev/after-load reload []
  (register-events!)
  (start))

(defonce server-token
  (-> (js/document.querySelector "meta[name=\"shadow-remote-token\"]")
      (.-content)))

(defn init []
  (sg/add-kv-table rt-ref ::m/ui
    {:validate-fn
     (fn [table key value]
       (when-not (keyword? key)
         (throw (ex-info "::m/ui can only take keyword keys" {:key key :value value}))))}
    {::m/current-page {:id :dashboard}
     ::m/init-complete? false
     ;; assume that the first connect will succeed
     ;; otherwise shows disconnect banner for a few ms on startup
     ::m/relay-ws-connected true
     ::m/ui-options {} ;; FIXME: should probably store this somewhere on the client side too
     ::m/runtimes []
     ::m/active-builds []
     ::m/tap-stream (list)
     ::m/tap-latest nil
     ::m/inspect
     {:current 0
      :stack
      [{:type :tap-panel}]}})

  ;; FIXME: figure out proper schema support
  ;; don't want to hard depend on spec or malli from grove
  ;; but it would be super useful to have this information in some place
  ;; FIXME: also sort out this messy mix of regular and namespaced keywords
  (sg/add-kv-table rt-ref ::m/runtime
    {:primary-key :runtime-id
     ;; :runtime-id number?
     ;; :runtime-info shadow-remote-runtime-info-map
     ;; :supported-opts set-of-keywords
     })

  (sg/add-kv-table rt-ref ::m/repl-stream
    {:primary-key :stream-id})

  (sg/add-kv-table rt-ref ::m/repl-history
    {:primary-key :id})

  (sg/add-kv-table rt-ref ::m/object
    {:primary-key :oid
     :attrs
     {:runtime-id {:references ::m/runtime}}
     ;; :oid number
     ;; :runtime-id number
     ;; :display-type keyword
     ;; :summary shadow-remote-obj-summary
     ;; :edn-limit preview-edn-string
     ;; :obj-edn string
     ;; :obj-pprint string
     ;; :fragment map-of-row-id-to-edn-limit-preview-for-row
     })

  (sg/add-kv-table rt-ref ::m/http-server
    {:primary-key ::m/http-server-id
     ;; ::m/http-server-id number
     ;; ::m/http-config dev-http-config-map
     ;; ::m/http-url string
     ;; ::m/https-url string
     })

  (sg/add-kv-table rt-ref ::m/build
    {:primary-key ::m/build-id
     ;; ::m/build-id keyword
     ;; ::m/build-target keyword
     ;; ::m/build-config-raw build-config-map
     ;; ::m/build-worker-active boolean
     })

  (sg/add-kv-table rt-ref ::m/build-config
    {:primary-key ::m/build-id
     ;; ::m/build-id keyword
     ;; ::m/build-target keyword
     ;; ::m/build-config-raw build-config-map
     ;; ::m/build-worker-active boolean
     })


  (sg/add-kv-table rt-ref ::m/error
    {:primary-key :error-id})

  ;; needs to be called before start since otherwise we can't process events triggered by any of these
  (register-events!)

  (transit/init! rt-ref)

  (history/init! rt-ref
    {:start-token "/dashboard"
     :root-el root-el})

  (sg/reg-fx rt-ref :http-api
    (http-fx/make-handler
      {:on-error {:e ::m/request-error!}
       :base-url "/api"
       :request-format :transit}))

  (relay-ws/init rt-ref server-token
    (fn []
      (relay-ws/cast! rt-ref
        {:op ::m/load-ui-options
         :to 1 ;; FIXME: don't blindly assume CLJ runtime is 1
         })

      (relay-ws/cast! rt-ref
        {:op ::m/db-sync-init!
         :to 1 ;; FIXME: don't blindly assume CLJ runtime is 1
         })))

  (sg/run-tx! rt-ref {:e ::m/init!})

  (start))