(ns shadow.devtools.server.util
  (:require [shadow.cljs.build :as cljs]
            [clojure.core.async :as async :refer (go thread <! >! alt!! alts!!)]
            [clojure.tools.logging :as log]
            [clojure.pprint :refer (pprint)]))


(defn async-logger [ch]
  (reify
    cljs/BuildLog
    (log*
      [_ state event]
      (async/offer! ch {:type :build-log
                        :event event}))))

(def null-log
  (reify
    cljs/BuildLog
    (log* [_ state msg])))


(defn server-thread
  [state-ref init-state dispatch-table {:keys [do-shutdown] :as options}]
  (let [chans
        (into [] (keys dispatch-table))]

    (thread
      (let [last-state
            (loop [state
                   init-state]
              (vreset! state-ref state)

              (let [[msg ch]
                    (alts!! chans)

                    handler
                    (get dispatch-table ch)]

                (if (nil? handler)
                  state
                  (if (nil? msg)
                    state
                    (-> state
                        (handler msg)
                        (recur))))))]

        (if-not do-shutdown
          last-state
          (do-shutdown last-state)
          )))))

(defn dump-tap [mult]
  (let [c (async/chan (async/sliding-buffer 1))]

    (async/tap mult c)

    (go (loop []
          (let [{:keys [type] :as out} (<! c)]
            (when-not (nil? out)
              (log/info (with-out-str (pprint out)))
              (recur))))
      (log/info [:output-stop])))
  mult)