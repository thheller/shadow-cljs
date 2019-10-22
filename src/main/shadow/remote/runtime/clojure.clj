(ns shadow.remote.runtime.clojure
  (:require
    [clojure.core.async :as async :refer (thread alt!! <!! >!!)]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.relay :as relay]
    [shadow.jvm-log :as log]
    [clojure.datafy :as d]))

(defn make-reply-fn [to-chan {:keys [msg-id tool-id] :as req}]
  (fn [res]
    (let [res (-> res
                  (cond->
                    msg-id
                    (assoc :msg-id msg-id)
                    tool-id
                    (assoc :tool-id tool-id)))]
      (>!! to-chan res))))

(defn runtime-loop
  [{:keys [state-ref tap-in from-relay to-relay]}]
  (loop []
    (alt!!
      tap-in
      ([obj]
       (when (some? obj)
         (let [obj-id (shared/register state-ref obj {:from :tap})]
           (doseq [tool-id (:tap-subs @state-ref)]
             (>!! to-relay {:op :tap :tool-id tool-id :obj-id obj-id})))
         (recur)))

      from-relay
      ([req]
       (when (some? req)
         (let [reply (make-reply-fn to-relay req)]
           (try
             (shared/process state-ref req reply)
             (catch Exception e
               (log/debug-ex e ::process-ex {:req req})
               (reply {:op :exception :ex (d/datafy e)}))))
         (recur)))

      (async/timeout 1000)
      ([_]
       ;; do some cleanup, gc, etc
       (recur)))))

(defn start [relay]
  (let [to-relay
        (async/chan 100)

        from-relay
        (relay/runtime-connect relay to-relay {:lang :clj})

        tap-in
        (-> (async/sliding-buffer 100)
            (async/chan))

        state-ref
        (atom
          (-> (shared/init-state)
              (assoc :relay-msg #(>!! to-relay %))))

        tap-fn
        (fn clj-tap [obj]
          ;; FIXME: I wish tap> would take a second argument for context
          ;; (tap> some-obj {:tap-mode :keep-latest :tap-stream :abc :title "result of something"})
          ;; so the UI can show more than just a generic id before requesting more
          ;; (tap> [:marker some-obj :other :things])
          ;; would need a marker to identify
          ;; only using tap> because it can be used without additional requires
          ;; could just add something to core as well though
          (when-not (nil? obj)
            ;; FIXME: does this need to go into the runtime-loop?
            ;; could do the work right here and just send it out
            (async/offer! tap-in obj)))

        thread
        (async/thread
          (runtime-loop {:state-ref state-ref
                         :tap-in tap-in
                         :from-relay from-relay
                         :to-relay to-relay}))]

    (add-tap tap-fn)

    {:state-ref state-ref
     :tap-fn tap-fn
     :tap-in tap-in
     :from-relay from-relay
     :to-relay to-relay
     :thread thread}))

(defn stop [{:keys [tap-fn tap-in to-relay thread] :as svc}]
  (remove-tap tap-fn)
  (async/close! to-relay)
  (async/close! tap-in)
  (<!! thread))

(comment
  (extend-protocol clojure.core.protocols/Datafiable
    java.io.File
    (datafy [^java.io.File file]
      {:absolute-path (.getAbsolutePath file)
       :name (.getName file)
       :size (.length file)})

    clojure.lang.Volatile
    (datafy [v]
      (with-meta [@v] (meta v)))

    java.util.concurrent.ThreadPoolExecutor
    (datafy [^java.util.concurrent.ThreadPoolExecutor tpe]
      {:active-count (.getActiveCount tpe)
       :completed-task-count (.getCompletedTaskCount tpe)
       :core-pool-size (.getCorePoolSize tpe)
       :largest-pool-size (.getLargestPoolSize tpe)
       :max-pool-size (.getMaximumPoolSize tpe)
       :task-count (.getTaskCount tpe)
       :is-terminated (.isTerminated tpe)
       :is-terminating (.isTerminating tpe)
       :is-shutdown (.isShutdown tpe)}
      )))