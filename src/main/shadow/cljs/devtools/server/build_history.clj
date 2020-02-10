(ns shadow.cljs.devtools.server.build-history
  (:require
    [clojure.core.async :as async :refer (go alt!)]
    [shadow.cljs.model :as m]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.build.log :as build-log]))

(defn update-build-status [state {:keys [type] :as msg}]
  (case type
    :build-configure
    (assoc state
      :status :compiling
      :active {}
      :log [])

    :build-start
    (-> state
        (dissoc :info :report :duration :warnings)
        (assoc :status :compiling
               :active {}
               :log []))

    :build-complete
    (let [{:keys [sources compiled] :as info}
          (:info msg)

          warnings
          (->> (for [{:keys [warnings resource-name]} sources
                     warning warnings]
                 (assoc warning :resource-name resource-name))
               (into []))]

      (assoc state
        :info info
        :status :completed
        :resources (count sources)
        :compiled (count compiled)
        :warnings warnings
        :duration
        (-> (- (or (get info :flush-complete)
                   (get info :compile-complete))
               (get info :compile-start))
            (double)
            (/ 1000))))

    ;; FIXME: how to transfer error? just the report?
    :build-failure
    (let [{:keys [report]} msg]
      (assoc state
        :status :failed
        :report report))

    :build-log
    (let [{:keys [timing-id timing] :as event} (:event msg)]
      (cond
        (not timing-id)
        (update state :log conj (build-log/event->str event))

        (= :enter timing)
        (update state :active assoc timing-id (assoc event ::m/msg (build-log/event->str event)))

        (= :exit timing)
        (-> state
            (update :active dissoc timing-id)
            (update :log conj (format "%s (%dms)"
                                (build-log/event->str event)
                                (:duration event))))

        :else
        state))

    ;; ignore all the rest for now
    ;; mostly REPL related things
    state))

(defn build-status-loop [{:keys [system-bus state-ref sub-chan]}]
  ;; FIXME: figure out which update frequency makes sense for the UI
  ;; 10fps is probably more than enough?
  (let [flush-delay 100
        flush-fn
        (fn [needs-flush]
          (doseq [build-id needs-flush]
            (let [state
                  (get @state-ref build-id)

                  flush-state
                  (-> state
                      (cond->
                        (not (contains? #{:failed :completed} (:status state)))
                        (dissoc :log)))

                  msg {:build-id build-id
                       :build-status flush-state}]

              (sys-bus/publish! system-bus ::m/build-status-update msg))))]

    (go (loop [needs-flush #{}
               timeout (async/timeout flush-delay)]
          (alt!
            timeout
            ([_]
              ;; don't include :log for regular updates since it gets too big
              ;; FIXME: should really look into only sending incremental updates
              (when (seq needs-flush)
                (flush-fn needs-flush))
              (recur #{} (async/timeout flush-delay)))

            sub-chan
            ([{:keys [build-id type] :as msg}]
              (if-not msg
                (flush-fn needs-flush)
                (do (swap! state-ref update build-id update-build-status msg)
                    (recur (conj needs-flush build-id) timeout)))))))))

(defn start [sys-bus]
  (let [sub-chan
        (-> (async/sliding-buffer 100)
            (async/chan))

        state-ref
        (atom {})

        svc
        {:system-bus sys-bus
         :sub-chan sub-chan
         :state-ref state-ref}]

    (build-status-loop svc)

    (sys-bus/sub sys-bus ::m/worker-broadcast sub-chan)
    (sys-bus/sub sys-bus ::m/build-log sub-chan)

    svc
    ))

(defn stop [{:keys [sub-chan] :as svc}]
  (async/close! sub-chan))


(comment
  (def x (:build-history @shadow.cljs.devtools.server.runtime/instance-ref))

  (prn [:x @(:state-ref x)])
  )

