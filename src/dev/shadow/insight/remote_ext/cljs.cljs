(ns shadow.insight.remote-ext.cljs
  (:require
    [shadow.insight :as-alias si]
    [shadow.insight.runtime :as sir]
    [shadow.remote.runtime.api :as api]
    [shadow.remote.runtime.shared :as srs]))

(defn notify! [{:keys [state-ref]} exec-ctx]
  (let [{:keys [listeners]} @state-ref]
    (reduce-kv
      (fn [_ id callback]
        (callback exec-ctx))
      nil
      listeners)))

(defn plan-execute-next!
  [{:keys [runtime state-ref] :as ext}
   {:keys [plan-id step] :as exec-ctx}]
  (let [{:keys [plan-ns blocks] :as plan}
        (get-in @state-ref [:plans plan-id])]

    (notify! ext exec-ctx)

    (if (>= step (count blocks))
      (js/console.log "plan execution done" exec-ctx plan)
      ;; execute next block
      (let [block (get-in plan [:blocks step])]
        (if (not= :expr (:type block))
          ;; only interested in exprs here, skip over anything else
          (recur ext (update exec-ctx :step inc))
          ;; eval expr, in last known ns for this runtime
          ;; FIXME: what about prints?
          (let [self-id (srs/get-client-id runtime)
                exec-ns (get-in exec-ctx [:exec-ns self-id])]

            (if (nil? exec-ns)
              ;; first block execution in this runtime, need to ensure namespace exists properly
              (api/cljs-eval runtime {:code (str "(ns " plan-ns " (:require [shadow.insight.runtime :as !]))")
                                      :ns 'shadow.insight.ui}
                (fn [{:keys [result results ns] :as info}]
                  (if (not= result :ok)
                    (js/console.error "ns configure failed" info)
                    (plan-execute-next! ext (assoc-in exec-ctx [:exec-ns self-id] ns)))))

              ;; ns exists, eval block code
              (api/cljs-eval runtime {:code (:source block) :ns exec-ns}
                (fn [{:keys [result results ns] :as info}]
                  (if (not= result :ok)
                    (js/console.error "execute step failed" info block)
                    (let [result (first results)
                          exec-ctx (assoc-in exec-ctx [:exec-ns self-id] ns)]
                      ;; shouldn't get here with more than 1 result right?
                      ;; would be a parser problem if the plan didn't properly separate expressions?
                      (assert (= 1 (count results)))

                      ;; ensure the ns we switched to knows the ! alias
                      ;; FIXME: runtime ns pollution, see clj
                      (if (= exec-ns ns)
                        (sir/-proceed-with-plan result ext exec-ctx)
                        (api/cljs-eval runtime {:code "(require '[shadow.insight.runtime :as !])" :ns ns}
                          (fn [info]
                            (sir/-proceed-with-plan result ext exec-ctx)
                            ))))))))))))))

(extend-protocol sir/IPlanAware
  sir/SwitchToLocal
  (-proceed-with-plan
    [this
     {:keys [runtime] :as ext}
     {:keys [driver-id step] :as exec-ctx}]
    (let [self-id (srs/get-client-id runtime)
          exec-ctx (assoc-in exec-ctx [:results step] {:runtime-id self-id :handoff driver-id})]
      (if (= driver-id self-id)
        ;; already in local, just do next step
        (plan-execute-next! ext (update exec-ctx :step inc))
        ;; handoff back to driver
        (srs/relay-msg runtime
          {:op ::si/handoff!
           :to driver-id
           :exec-ctx (update exec-ctx :step inc)}))))

  sir/SwitchToRemote
  (-proceed-with-plan
    [this
     {:keys [runtime] :as ext}
     {:keys [step driver-id] :as exec-ctx}]
    (let [self-id (srs/get-client-id runtime)]
      (if (not= driver-id self-id)
        ;; tell driver to switch, we don't have the runtime info
        (let [exec-ctx (assoc-in exec-ctx [:results step] {:runtime-id self-id :handoff driver-id})]
          (srs/relay-msg runtime
            {:op ::si/switch-to-remote!
             :to driver-id
             :opts (.-opts this)
             :exec-ctx (update exec-ctx :step inc)}))

        ;; FIXME: in driver, actually pick based on opts
        (let [target 1
              exec-ctx (assoc-in exec-ctx [:results step] {:runtime-id self-id :handoff target})]
          (srs/relay-msg runtime
            {:op ::si/handoff!
             :to target
             :exec-ctx (update exec-ctx :step inc)}))))))

;; called by UI directly

(defn plan-execute!
  [{:keys [runtime state-ref] :as ext} {:keys [plan-id plan-ns] :as plan}]
  (let [driver-id
        (srs/get-client-id runtime)

        exec-ctx
        {:exec-id (str (random-uuid)) ;; just some unique thing, not actually uuid because overhead in transfer
         :driver-id driver-id
         :plan-id plan-id
         :step 0}]

    (swap! state-ref assoc-in [:plans plan-id] plan)

    (js/console.log "begin execution" exec-ctx plan)

    (plan-execute-next! ext exec-ctx)))

(defn add-listener [{:keys [state-ref] :as ext} listen-id callback]
  {:pre [(fn? callback)]}
  (swap! state-ref assoc-in [:listeners listen-id] callback)
  ext)

(defn remove-listener [{:keys [state-ref] :as ext} listen-id]
  (swap! state-ref update :listeners dissoc listen-id)
  ext)

;; (!/in-remote {:lang :clj}) while not in driver UI context sends this to us
(defn switch-to-remote! [{:keys [runtime] :as svc} {:keys [opts exec-ctx] :as msg}]
  ;; FIXME: actually select
  (let [target 1]
    (srs/relay-msg runtime
      {:op ::si/handoff!
       :to target
       ;; runtime that sent this already bumped
       :exec-ctx exec-ctx})))

(defn result!
  [{:keys [runtime] :as ext} {:keys [ref-oid plan-id exec-id step] :as msg}]

  )