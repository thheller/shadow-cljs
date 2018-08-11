(ns demo.dev-setup
  (:require
    [clojure.core.async :as async :refer (go <!)]
    [shadow.cljs.devtools.api :as shadow]
    [shadow.cljs.devtools.server.worker :as worker]
    ))

(defn handle-watch-message [{:keys [type] :as msg}]
  (prn [:watch-msg type]))

(defn start
  {:shadow/requires-server true}
  [& args]
  (shadow/watch :browser)
  (shadow/watch :script)

  (let [worker (shadow/get-worker :script)
        watch-chan (-> (async/sliding-buffer 10)
                       (async/chan))]
    (go (loop []
          (when-let [msg (<! watch-chan)]
            (handle-watch-msg msg)
            (recur)
            )))

    (worker/watch worker watch-chan)))
