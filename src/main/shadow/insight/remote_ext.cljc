(ns shadow.insight.remote-ext
  (:require
    [#?(:clj shadow.insight.remote-ext.clj :cljs shadow.insight.remote-ext.cljs) :as impl]
    [shadow.insight :as-alias si]
    [shadow.insight.runtime :as sir]
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.obj-support :as obj-support]
    [shadow.remote.runtime.shared :as srs]))

(defn handoff-ack! [svc msg])

(defn handoff!
  [{:keys [runtime state-ref] :as ext} {:keys [exec-ctx] :as msg}]
  (let [{:keys [plan-id exec-id driver-id step]} exec-ctx
        plan (get-in @state-ref [:plans plan-id])]

    (if-not plan
      ;; don't know about plan yet, request and repeat handoff
      (srs/call runtime
        {:op ::si/request-plan! :to driver-id :plan-id plan-id}
        {::si/plan!
         (fn [{:keys [plan]}]
           (swap! state-ref assoc-in [:plans plan-id] plan)
           (handoff! ext msg))})

      ;; know the plan, tell driver we are running things, execute next step
      (do (srs/relay-msg runtime {:op ::si/handoff-ack! :to driver-id :plan-id plan-id :exec-id exec-id :step step})
          (impl/plan-execute-next! ext exec-ctx)
          ))))

(defn request-plan! [{:keys [runtime state-ref] :as ext} {:keys [plan-id] :as msg}]
  (let [plan (get-in @state-ref [:plans plan-id])]
    (srs/reply runtime msg {:op ::si/plan! :plan (dissoc plan :results)})))

(defn start [runtime obj-support]
  (let [svc {:runtime runtime :obj-support obj-support :state-ref (atom {})}]

    (p/add-extension runtime
      ::ext
      {:ops
       {::si/handoff! #(handoff! svc %)
        ::si/handoff-ack! #(handoff-ack! svc %)
        ::si/request-plan! #(request-plan! svc %)
        #?@(:clj
            [::si/fetch! #(impl/fetch! svc %)]
            :cljs
            [::si/switch-to-remote! #(impl/switch-to-remote! svc %)
             ::si/result! #(impl/result! svc %)])}})

    svc))

(defn stop [{:keys [runtime] :as svc}]
  (p/del-extension runtime ::ext))

(extend-protocol sir/IPlanAware
  #?(:cljs default :clj Object)
  (-proceed-with-plan
    [result
     {:keys [runtime obj-support state-ref] :as ext}
     {:keys [exec-id plan-id driver-id step] :as exec-ctx}]

    (let [self-id
          (srs/get-client-id runtime)

          in-driver?
          (= driver-id self-id)

          simple-val?
          (obj-support/simple-value? result)]

      (if simple-val?
        ;; simple value we can just send directly
        (do (srs/relay-msg runtime
              {:op ::si/result!
               :to driver-id
               :value result
               :plan-id plan-id
               :exec-id exec-id
               :step step})

            (impl/plan-execute-next! ext
              (-> exec-ctx
                  (update :step inc)
                  (assoc-in [:results step] {:runtime self-id :value result}))))

        ;; complex vals only send ref
        ;; keeping the result itself out of exec-ctx, so it isn't transferred back and forth
        ;; only keeping track of the remote handle, so anything can refer to it if needed
        (let [ref-oid (obj-support/register obj-support result {:exec-id exec-id :plan-id plan-id :step step})]
          (srs/relay-msg runtime
            {:op ::si/result!
             :to driver-id
             :ref-oid ref-oid
             :plan-id plan-id
             :exec-id exec-id
             :step step})

          (impl/plan-execute-next! ext
            (-> exec-ctx
                (update :step inc)
                (assoc-in [:results step] {:runtime self-id :ref-oid ref-oid}))))))))
