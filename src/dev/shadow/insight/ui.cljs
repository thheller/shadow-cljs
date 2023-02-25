(ns shadow.insight.ui
  (:require
    [cljs.pprint :refer (pprint)]
    [shadow.grove :as sg :refer (defc <<)]
    [shadow.grove.db :as db]
    [shadow.grove.eql-query :as eql]
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
    :primary-key :plan-id
    :joins {:blocks [:many ::block]}}

   ::block
   {:type :entity
    :primary-key [:plan-id :idx]}})

(defonce data-ref
  (-> {}
      (db/configure schema)
      (atom)))

(defonce rt-ref
  (-> {}
      (sg/prepare data-ref :insight-driver)))

(defmethod eql/attr :is-local-result? [env db current query-part query-params]
  (or (contains? (:result current) :value)
      (= (::runtime-id db) (:runtime (:result current)))))


(defc ui-block [ident]
  (bind {:keys [idx result] :as block}
    (sg/query-ident ident [:db/all :is-local-result?]))

  (render
    (case (:type block)
      :text
      (<< [:div {:dom/inner-html (:html block)}])

      :comment
      nil

      :expr
      (cond
        (not result)
        (<< [:div
             [:pre {:dom/inner-html (:source block)}]
             [:pre "Waiting ..."]])

        (:hidden result)
        nil ;; FIXME: render something to at least make it possible to reveal

        :else
        (<< [:div
             [:pre {:dom/inner-html (:source block)}]
             [:div (pr-str (:is-local-result? block))]
             [:pre (pr-str result)]]))

      :directives
      (<< [:pre (:source block)])

      (<< [:div (pr-str block)]))))

(defc ui-plan [ident]
  (bind {:keys [blocks] :as data}
    (sg/query-ident ident))

  (render
    (sg/simple-seq blocks ui-block)))

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

                (let [self-id (srs/get-client-id runtime)
                      plan-id (:plan-id plan)
                      plan-ident (db/make-ident ::plan plan-id)]

                  (sg/run-tx! rt-ref
                    (fn [env]
                      (-> env
                          (assoc-in [:db ::runtime-id] self-id)
                          (update :db db/add ::plan (update plan :blocks (fn [blocks] (mapv #(assoc %1 :plan-id plan-id) blocks)))
                            (fn [db ident]
                              (assoc db ::active-plan ident))))))

                  (insight-ext-cljs/add-listener insight-ext ::ui
                    (fn [{:keys [results] :as exec-ctx}]
                      (sg/run-tx! rt-ref
                        (fn [env]
                          (update env :db
                            (fn [db]
                              (reduce-kv
                                (fn [db idx result]
                                  (let [block-ident (db/make-ident ::block [plan-id idx])]
                                    (assoc-in db [block-ident :result] result)))
                                db
                                results)))))))

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