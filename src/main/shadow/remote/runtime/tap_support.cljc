(ns shadow.remote.runtime.tap-support
  (:require
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.obj-support :as obj]))

(defn tap-subscribe
  [{:keys [subs-ref]} {:keys [tid]}]
  (swap! subs-ref conj tid))

(defn tap-unsubscribe
  [{:keys [subs-ref]} {:keys [tid]}]
  (swap! subs-ref disj tid))

(defn request-tap-history
  [{:keys [obj-support runtime]}
   {:keys [num] :or {num 10} :as msg}]
  ;; FIXME: add actual API fn, don't reach into obj-svc directly
  (let [{:keys [state-ref]} obj-support

        tap-ids
        (->> (:objects @state-ref)
             (vals)
             (filter #(= :tap (get-in % [:obj-info :from])))
             (sort-by #(get-in % [:obj-info :added-at]))
             (reverse)
             (take num)
             (map :oid)
             (into []))]

    (p/reply runtime msg {:op :tap-history
                          :oids tap-ids})))

(defn tool-disconnect
  [{:keys [subs-ref] :as svc} tid]
  (swap! subs-ref disj tid))

(defn start [runtime obj-support]
  (let [subs-ref
        (atom #{})

        tap-fn
        (fn runtime-tap [obj]
          (when (some? obj)
            (let [oid (obj/register obj-support obj {:from :tap})]
              (doseq [tid @subs-ref]
                (p/relay-msg runtime {:op :tap :tid tid :oid oid})))))

        svc
        {:runtime runtime
         :obj-support obj-support
         :tap-fn tap-fn
         :subs-ref subs-ref}]

    (p/add-extension runtime
      ::ext
      {:ops
       ;; would be nicer to just pass tap-subscribe and have the runtime
       ;; automatically pass extra args. but this makes everything REPL unfriendly
       ;; and will require a runtime restart for every op change
       ;; this way only adding ops requires a restart
       {:tap-subscribe #(tap-subscribe svc %)
        :tap-unsubscribe #(tap-unsubscribe svc %)
        :request-tap-history #(request-tap-history svc %)}
       :on-tool-disconnect #(tool-disconnect svc %)})

    (add-tap tap-fn)
    svc))

(defn stop [{:keys [tap-fn runtime] :as svc}]
  (remove-tap tap-fn)
  (p/del-extension runtime ::ext))
