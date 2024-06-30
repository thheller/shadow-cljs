(ns demo.notify-plugin
  (:require
    [clojure.core.async :as async]
    [shadow.jvm-log :as log]
    [shadow.cljs :as-alias m]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    ))

(def plugin
  {:requires-server true
   :depends-on [:system-bus]
   :start
   (fn [sys-bus]
     (log/debug ::start)
     (let [sub-chan (async/chan 512)]
       (sys-bus/sub sys-bus ::m/worker-broadcast sub-chan)
       {:chan sub-chan
        :thread
        (async/thread
          (loop []
            (when-some [msg (async/<!! sub-chan)]
              ;; best use (tap> msg) to see exact structure of the msg data
              (case (:type msg)
                :build-start
                (prn [:build-started (:build-id msg)])

                :build-complete
                (prn [:build-completed-successfully (:build-id msg)])

                :build-failure
                (prn [:build-failed (:build-id msg)])

                ;; ignore others (currently only :build-configure, only emitted when build config changes)
                nil)

              (recur))))}))
   :stop
   (fn [{:keys [chan thread] :as svc}]
     (log/debug ::stop)
     (async/close! chan)
     ;; wait for proper shutdown
     (async/<!! thread)
     ::done)})