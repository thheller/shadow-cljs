(ns shadow.lang.json-rpc.socket-server
  (:require [clojure.core.async :as async]
            [shadow.lang.server :as server]
            [shadow.lang.json-rpc.io :as io])
  (:import (java.net SocketException ServerSocket)))


(defn socket-loop [system socket server-stop]
  (io/io-loop
    #(server/server-loop system %1 %2)
    (.getInputStream socket)
    (.getOutputStream socket)
    server-stop)
  (.close socket))

(defn accept-loop [system server-socket server-stop]
  (loop []
    (when-let [socket
               (try
                 (.accept server-socket)
                 (catch SocketException e nil))]
      (doto (Thread. #(socket-loop system socket server-stop))
        (.start))
      (recur))))

(defn start [{:keys [config] :as system}]
  (let [{:keys [host port]}
        (:remote config)

        ss
        (ServerSocket. port)

        server-stop
        (async/chan)

        accept-thread
        (doto (Thread. #(accept-loop system ss server-stop))
          (.start))]

    {:server-socket ss
     :server-stop server-stop
     :accept-thread accept-thread}
    ))

(defn stop [{:keys [server-stop server-socket] :as svc}]
  (async/close! server-stop)
  (.close server-socket))

(comment
  (def x (start {}))

  (stop x)
  )


