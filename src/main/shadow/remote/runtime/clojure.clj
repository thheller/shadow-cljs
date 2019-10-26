(ns shadow.remote.runtime.clojure
  (:require
    [clojure.core.async :as async :refer (thread alt!! <!! >!!)]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.relay :as relay]
    [shadow.jvm-log :as log]
    [clojure.datafy :as d])
  (:import [java.util.concurrent TimeUnit Executors ExecutorService Future]))

(defn make-reply-fn [to-chan {:keys [mid tid] :as req}]
  (fn [res]
    (let [res (-> res
                  (cond->
                    mid
                    (assoc :mid mid)
                    tid
                    (assoc :tid tid)))]
      (>!! to-chan res))))

(defn check-tasks [tasks]
  (reduce-kv
    (fn [tasks ^Future fut msg]
      (if-not (.isDone fut)
        tasks
        (dissoc tasks fut)))
    tasks
    tasks))

(defn runtime-loop
  [{:keys [^ExecutorService ex state-ref from-relay to-relay]}]
  (loop []
    (alt!!
      from-relay
      ([req]
       (when (some? req)
         ;; using a thread-pool so slow messages don't delay others
         ;; FIXME: maybe all tasks should have an optional :timeout?
         ;; can't reliably "kill" a running task though
         (let [fut
               (.submit ex
                 ^Callable
                 (fn []
                   (let [reply (make-reply-fn to-relay req)]
                     (try
                       (shared/process state-ref req reply)
                       (catch Throwable e
                         (log/debug-ex e ::process-ex {:req req})
                         (reply {:op :exception :ex (d/datafy e)}))))))]

           ;; keeping and cleaning tasks for obversability purposes only for now
           ;; could maybe log warnings if stuff takes too long
           (swap! state-ref assoc-in [:tasks fut] (assoc req ::started (System/currentTimeMillis))))
         (recur)))

      (async/timeout 1000)
      ([_]
       (shared/basic-gc state-ref)
       (swap! state-ref update :tasks check-tasks)
       (recur)))))

(defn start [relay]
  (let [to-relay
        (-> (async/sliding-buffer 100)
            ;; ok to drop taps when we can't keep up
            ;; FIXME: needs adjustments in the websocket parts probably
            (async/chan))

        from-relay
        (relay/runtime-connect relay to-relay {:lang :clj})

        ex
        (Executors/newCachedThreadPool)

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
          (when (some? obj)
            (let [oid (shared/register state-ref obj {:from :tap})]
              (doseq [tid (:tap-subs @state-ref)]
                (>!! to-relay {:op :tap :tid tid :oid oid})))))

        thread
        (async/thread
          (runtime-loop {:ex ex
                         :state-ref state-ref
                         :from-relay from-relay
                         :to-relay to-relay}))]

    (add-tap tap-fn)

    {:state-ref state-ref
     :ex ex
     :tap-fn tap-fn
     :from-relay from-relay
     :to-relay to-relay
     :thread thread}))

(defn stop [{:keys [ex tap-fn to-relay from-relay thread] :as svc}]
  (remove-tap tap-fn)
  (async/close! to-relay)
  (async/close! from-relay)

  (.shutdown ex)
  (when-not (.awaitTermination ex 10 TimeUnit/SECONDS)
    (.shutdownNow ex))

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