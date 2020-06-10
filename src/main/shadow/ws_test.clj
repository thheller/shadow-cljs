(ns shadow.ws-test
  (:require
    [clojure.core.async :as async :refer (go <! >! alt!)]
    [shadow.undertow :as undertow]
    [clojure.java.io :as io]))

(defn -main [port root]
  (println "Starting ...")
  (let [handler
        (fn [req]
          {:status 404
           :headers {"content-type" "text/plain; charset=utf-8"}
           :body "Only here for websocket testing purposes."})

        ws-handler
        (fn [req]
          (prn [:ws-req (:uri req)])
          (let [ws-in (async/chan 10)
                ws-out (async/chan 10)
                ws-loop
                (go (loop [last-msg (System/currentTimeMillis)]
                      (alt!
                        ws-in
                        ([msg]
                         (when (some? msg)
                           (prn [:from-client msg])
                           (recur (System/currentTimeMillis))))

                        (async/timeout 1000)
                        ([_]
                         (if-not (< (System/currentTimeMillis) (+ last-msg 5000))
                           (prn [:ping-timeout])
                           (do (>! ws-out "ping")
                               (recur last-msg))))))

                    (prn [:client-loop-end]))]

            {:ws-in ws-in
             :ws-out ws-out
             :ws-loop ws-loop}))

        http
        (undertow/start {:host "0.0.0.0" :port (Long/parseLong port)}
          [::undertow/ws-upgrade
           [::undertow/ws-ring {:handler-fn ws-handler}]
           [::undertow/file {:root-dir (io/file root)}
            [::undertow/blocking
             [::undertow/ring {:handler-fn handler}]]]])]

    (println "Waiting for requests ...")
    ;; idle spin main thread, nothing to do here really
    (loop []
      (Thread/sleep 100)
      (recur))))
