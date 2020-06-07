(ns shadow.remote.runtime.clj.local
  (:require
    [clojure.core.async :as async :refer (thread alt!! <!! >!!)]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.obj-support]
    [shadow.remote.relay.api :as relay]
    [shadow.jvm-log :as log]
    [clojure.datafy :as d]
    [shadow.remote.runtime.api :as p])
  (:import [java.util.concurrent TimeUnit Executors ExecutorService Future]))

(defn check-tasks [tasks]
  (reduce-kv
    (fn [tasks ^Future fut msg]
      (if-not (.isDone fut)
        tasks
        (dissoc tasks fut)))
    tasks
    tasks))

(defn runtime-loop
  [{:keys [^ExecutorService ex state-ref from-relay to-relay] :as runtime}]

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
                   (try
                     (shared/process runtime req)
                     (catch Throwable e
                       (log/debug-ex e ::process-ex {:req req})
                       (>!! to-relay {:op :exception
                                      :call-id (:call-id req)
                                      :to (:from req)
                                      :ex (d/datafy e)})))))]

           ;; keeping and cleaning tasks for obversability purposes only for now
           ;; could maybe log warnings if stuff takes too long
           (swap! state-ref assoc-in [:tasks fut] (assoc req ::started (System/currentTimeMillis))))
         (recur)))

      (async/timeout 1000)
      ([_]
       (swap! state-ref update :tasks check-tasks)
       (shared/run-on-idle state-ref)
       (recur)))))

(defrecord ClojureRuntime [state-ref ex from-relay to-relay]
  p/IRuntime
  (relay-msg [_ msg]
    (when-not (async/offer! to-relay msg)
      (log/warn ::dropped-message msg)))
  (add-extension [this key spec]
    (shared/add-extension this key spec))
  (del-extension [this key]
    (shared/del-extension this key)))

(defn start [relay]
  (let [to-relay
        (-> (async/sliding-buffer 100)
            ;; ok to drop taps when we can't keep up
            ;; FIXME: needs adjustments in the websocket parts probably
            (async/chan))

        from-relay
        (async/chan 256)

        _
        (relay/connect relay to-relay from-relay {})

        ex
        (Executors/newCachedThreadPool)

        state-ref
        (-> {:type :runtime
             :lang :clj
             :desc "JVM Clojure Runtime"}
            (shared/init-state)
            (atom))

        runtime
        (doto (ClojureRuntime. state-ref ex from-relay to-relay)
          (shared/add-defaults))]

    ;; FIXME: should keep reference to thread so we can properly wait for its end
    (async/thread
      (runtime-loop runtime))

    runtime))

(defn stop [{:keys [ex to-relay from-relay] :as svc}]
  (async/close! to-relay)
  (async/close! from-relay)

  (.shutdown ex)
  (when-not (.awaitTermination ex 10 TimeUnit/SECONDS)
    (.shutdownNow ex)))

(comment

  (require '[shadow.remote.relay.local :as rl])

  (def r (rl/start))

  (prn r)

  (rl/stop r)

  (def x (start r))

  (prn x)

  (stop x)

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