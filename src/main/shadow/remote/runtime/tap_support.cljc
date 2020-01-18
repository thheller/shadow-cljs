(ns shadow.remote.runtime.tap-support
  (:require
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.obj-support :as obj]))

(defn tap-subscribe
  [{:keys [subs-ref obj-support runtime] :as svc} {:keys [tid summary history num] :or {num 10} :as msg}]
  (swap! subs-ref assoc tid msg)
  ;; FIXME: should this always confirm?
  ;; tool may want to do stuff even if it didn't request a history?
  ;; but it can do so optimistically and just receive taps?

  ;; we need an option to send out the history because of concurrency issues
  ;; otherwise it may do a :request-tap-history before :tap-subscribe
  ;; which may cause it to miss taps inbetween
  ;; or after which means it may have received taps before receiving the history
  (when history
    (p/reply runtime msg
      {:op :tap-subscribed
       :history (->> (obj/get-tap-history obj-support num)
                     ;; FIXME: only send summary if requested
                     (map (fn [oid] {:oid oid :summary (obj/obj-describe* obj-support oid)}))
                     (into []))})))

(defn tap-unsubscribe
  [{:keys [subs-ref]} {:keys [tid]}]
  (swap! subs-ref dissoc tid))

(defn request-tap-history
  [{:keys [obj-support runtime]}
   {:keys [num] :or {num 10} :as msg}]
  (let [tap-ids (obj/get-tap-history obj-support num)]
    (p/reply runtime msg {:op :tap-history
                          :oids tap-ids})))

(defn tool-disconnect
  [{:keys [subs-ref] :as svc} tid]
  (swap! subs-ref dissoc tid))

(defn start [runtime obj-support]
  (let [subs-ref
        (atom {})

        tap-fn
        (fn runtime-tap [obj]
          (when (some? obj)
            (let [oid (obj/register obj-support obj {:from :tap})]
              (doseq [[tid tap-config] @subs-ref]
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
