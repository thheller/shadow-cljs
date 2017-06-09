(ns shadow.cljs.devtools.remote.tcp-server
  (:require [clojure.core.async :as async :refer (>!! go >! <!)]
            [shadow.cljs.devtools.remote.protocol :as protocol]
            [clojure.java.io :as io]
            [shadow.cljs.devtools.remote.api :as remote-api])
  (:import (java.net SocketException ServerSocket)))

(defn stream-reader [is input-chan]
  (let [in (io/reader is)

        buffer
        (char-array 1024)]
    (try
      (loop []
        (let [read (.read in buffer)]
          (if (= -1 read)
            (async/close! input-chan)

            (let [chunk (String. buffer 0 read)]
              (when-not (>!! input-chan chunk)
                (prn [:dropped chunk]))
              (recur)
              ))))
      ;; ignore this
      (catch SocketException e
        nil)
      ;; FIXME: ignoring what happened, just assuming the input-stream is dead
      (catch Exception e
        (prn [:stream-reader e])
        nil)
      (finally
        (async/close! input-chan)))))

(defn socket-loop [socket remote-api]
  (let [tcp-in
        (async/chan 10)

        msg-in
        (async/chan 10)

        msg-out
        (async/chan 10)

        tcp-out
        (io/writer (.getOutputStream socket))

        tcp-write
        (fn [chunk]
          (doto tcp-out
            (.write chunk)
            (.flush )))]

    (protocol/read-loop tcp-in msg-in)
    (protocol/write-loop msg-out tcp-write)

    (go (<! (remote-api/client remote-api msg-in msg-out))
        ;; server decided the client should leave
        (.close socket))

    (stream-reader (.getInputStream socket) tcp-in)

    ;; stream eof
    (async/close! tcp-in)
    (async/close! msg-in)
    (async/close! msg-out)
    ;; probably already closed
    (.close socket)
    ))

(defn accept-loop [server-socket remote-api]
  (loop []
    (when-let [socket
               (try
                 (.accept server-socket)
                 (catch SocketException e nil))]
      (doto (Thread. #(socket-loop socket remote-api))
        (.start))
      (recur))))

(defn start [{:keys [host port]} remote-api]
  (let [ss
        (ServerSocket. port)

        server-stop
        (async/chan)

        accept-thread
        (doto (Thread. #(accept-loop ss remote-api))
          (.start))]

    {:server-socket ss
     :server-stop server-stop
     :accept-thread accept-thread}
    ))

(defn stop [{:keys [server-socket] :as svc}]
  (.close server-socket))
