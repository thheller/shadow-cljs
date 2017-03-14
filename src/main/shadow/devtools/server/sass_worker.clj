(ns shadow.devtools.server.sass-worker
  (:require [shadow.devtools.sass :as sass]
            [clojure.core.async :as async :refer (alt!! <!!)]
            [shadow.devtools.server.system-bus :as sys-bus]
            [shadow.devtools.server.system-msg :as sys-msg]
            ))

(defn svc? [x]
  (and (map? x) (::service x)))

(defn worker-thread [control system-bus css-watch packages]
  (loop []
    (alt!!
      control
      ([_]
        ::terminated)

      css-watch
      ([msg]
        (when (some? msg)
          ;; FIXME: send as one message instead of a msg per pkg?
          (doseq [pkg (sass/build-packages packages)]
            (let [msg
                  (-> pkg
                      (select-keys [:name :public-path :manifest])
                      (assoc :type :css/reload))]
              (sys-bus/publish! system-bus ::sys-msg/css-reload msg)))
          (recur)
          )))))

(defn start [system-bus packages]
  (let [control
        (async/chan)

        css-watch
        (async/chan (async/sliding-buffer 1))

        worker
        (async/thread (worker-thread control system-bus css-watch packages))]

    (sys-bus/sub system-bus ::sys-msg/sass-watch css-watch)

    {::service true
     :packages packages
     :system-bus system-bus
     :control control
     :css-watch css-watch
     :worker worker
     }))

(defn stop [{:keys [worker control css-watch] :as svc}]
  {:pre [(svc? svc)]}
  (async/close! control)
  (async/close! css-watch)
  (<!! worker))
