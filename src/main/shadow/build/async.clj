(ns shadow.build.async
  (:import [java.util.concurrent ExecutorService]))

(defn queue-task
  [{:keys [executor pending-tasks-ref] :as state} fn]
  (if-not executor
    (fn)
    ;; FIXME: no backpressure, can potentially eat up a whole lot of memory
    ;; if too many tasks get queued before actually completing
    (let [future
          (.submit
            ^ExecutorService executor
            ;; enforce the nil return value, would otherwise maybe retain
            ;; some unnecessary state in form of the return value
            ^Callable (bound-fn [] (fn) nil))]
      (swap! pending-tasks-ref conj future)))
  state)

(defn wait-for-pending-tasks!
  [{:keys [pending-tasks-ref] :as state}]
  ;; using an atom for this feels so dirty
  ;; but return values are ignored anyways so it doesn't matter too much
  (swap! pending-tasks-ref
    (fn [tasks]
      (run! deref tasks)
      []))
  state)
