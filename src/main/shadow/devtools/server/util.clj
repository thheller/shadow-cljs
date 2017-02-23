(ns shadow.devtools.server.util
  (:require [shadow.cljs.log :as shadow-log]
            [clojure.core.async :as async :refer (go thread <! >! alt!! alts!!)]
            [shadow.cljs.build :as cljs]))

(defn async-logger [ch]
  (reify
    shadow-log/BuildLog
    (log*
      [_ state event]
      (async/offer! ch {:type :build-log
                        :event event}))))

(def null-log
  (reify
    shadow-log/BuildLog
    (log* [_ state msg])))

(defn print-worker-out [x]
  (locking cljs/stdout-lock
    (case (:type x)
      :build-log
      (println (shadow-log/event-text (:event x)))

      :build-start
      (println (format "[%s] Build started." (-> x :build-config :id)))

      :build-complete
      (let [{:keys [info build-config]} x
            {:keys [sources compiled warnings]} info]

        (println (format "[%s] Build completed. (%d files, %d compiled, %d warnings)"
                   (:id build-config)
                   (count sources)
                   (count compiled)
                   (count warnings)))

        (when (seq warnings)
          (println (format "====== %d Warnings" (count warnings)))
          (doseq [{:keys [msg line column source-name] :as w} warnings]
            (println (str "WARNING: " msg " (" source-name " at " line ":" column ") ")))
          (println "======")))

      :build-shutdown
      (println "Build shutdown.")

      :repl-action
      :ignored

      :worker-shutdown
      (println "Worker shutdown.")

      ;; default
      (prn [:log x]))))

(defn stdout-dump []
  (let [chan
        (-> (async/sliding-buffer 1)
            (async/chan))]

    (async/go
      (loop []
        (when-some [x (<! chan)]
          (print-worker-out x)
          (recur)
          )))

    chan
    ))

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
                    (let [state
                          (try
                            (handler state msg)
                            (catch Exception e
                              (prn [:error-occured-in-server e])
                              state))]
                      (recur state))
                    ))))]

        (if-not do-shutdown
          last-state
          (do-shutdown last-state)
          )))))
