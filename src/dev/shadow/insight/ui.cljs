(ns shadow.insight.ui
  (:require
    [cljs.pprint :refer (pprint)]
    [shadow.grove :as sg :refer (defc <<)]
    [shadow.grove.db :as db]
    [shadow.insight :as-alias si]
    [shadow.insight.runtime :as sir]
    [shadow.insight.remote-ext :as insight-ext]
    [shadow.insight.remote-ext.cljs :as insight-ext-cljs]
    [shadow.cljs.devtools.client.shared :as client]
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.api :as api]
    [shadow.remote.runtime.shared :as srs]))

(def schema
  {::plan
   {:type :entity
    :primary-key :plan-id}})

(defonce data-ref
  (-> {}
      (db/configure schema)
      (atom)))

(defonce rt-ref
  (-> {}
      (sg/prepare data-ref :insight-driver)))

(defc ui-plan [ident]
  (bind {::keys [runtime-id]}
    (sg/query-root [::runtime-id]))

  (bind {:keys [blocks exec-ctx] :as data}
    (sg/query-ident ident))

  (render
    (<< [:pre
         (with-out-str (pprint (dissoc exec-ctx :results)))]

        (sg/simple-seq blocks
          (fn [block idx]
            (case (:type block)
              :text
              (<< [:div {:dom/inner-html (:html block)}])

              :comment
              nil

              :expr
              (let [{:keys [hidden] :as res} (get-in exec-ctx [:results idx])]
                (if hidden
                  nil ;; FIXME: render something to at least make it possible to reveal
                  (<< [:div
                       [:pre {:dom/inner-html (:source block)}]
                       [:pre (pr-str (get-in exec-ctx [:results idx]))]])))

              :directives
              (<< [:pre (:source block)])

              (<< [:div (pr-str block)]))
            )))))

(defc ui-root []
  (bind {::keys [active-plan]}
    (sg/query-root [::active-plan]))

  (render
    (if-not active-plan
      (<< [:div "Loading ..."])
      (ui-plan active-plan))))

(defn render []
  (sg/render rt-ref js/document.body (ui-root)))

(defn ^:dev/after-load reload! []
  (render))

(defn init []
  ;; FIXME: should this really be using the existing shadow.remote websocket used for hot-reload/repl?
  ;; should be ok but might better to keep it separate

  ;; start talking to ui on connect
  (client/add-plugin! :insight-ext #{:obj-support}
    (fn [{:keys [runtime obj-support] :as deps}]
      (insight-ext/start runtime obj-support))
    insight-ext/stop)

  ;; starting UI properly when remote websocket connects
  ;; FIXME: add all the deps the execution might need
  (client/add-plugin! ::ui #{:insight-ext}
    (fn [{:keys [runtime insight-ext] :as svc}]

      (swap! rt-ref assoc ::runtime runtime ::insight-ext insight-ext)

      (p/add-extension runtime
        ::ext
        {:on-welcome
         (fn []
           ;; for now just request a dummy plan
           (srs/call runtime
             {:op ::si/fetch!
              :to 1
              :file "foo"}
             {::si/plan!
              (fn [{:keys [plan] :as msg}]
                (let [ident (db/make-ident ::plan (:plan-id plan))]

                  (sg/run-tx! rt-ref
                    (fn [env]
                      (-> env
                          (assoc-in [:db ::runtime-id] (srs/get-client-id runtime))
                          (assoc-in [:db ident] (assoc plan :db/ident ident))
                          (assoc-in [:db ::active-plan] ident))))

                  (insight-ext-cljs/add-listener insight-ext ::ui
                    (fn [exec-ctx]
                      (sg/run-tx! rt-ref
                        (fn [env]
                          (assoc-in env [:db ident :exec-ctx] exec-ctx)))))

                  (insight-ext-cljs/plan-execute! insight-ext plan))
                )}))

         :on-disconnect
         (fn []
           (insight-ext-cljs/remove-listener insight-ext ::ui))

         :ops
         {}})

      svc)

    (fn [{:keys [runtime] :as svc}]
      (p/del-extension runtime ::ui)))


  (sg/reg-fx rt-ref :ws-send
    (fn [{::keys [runtime] :as env} message]
      (srs/relay-msg runtime message)))

  (when ^boolean js/goog.DEBUG
    (swap! rt-ref assoc :shadow.grove.runtime/tx-reporter
      (fn [report]
        (let [e (-> report :event :e)]
          (js/console.log e report)))))

  #_(sg/reg-fx rt-ref :ws-call
      (fn [{::keys [runtime] :as env} {:keys [op handlers]}]
        (srs/call runtime op
          (reduce-kv
            (fn [handlers result-op result-ev]
              (assoc handlers result-op (fn [msg] (js/console.log "yo result" result-ev env msg))))
            {}
            handlers))))

  (render))