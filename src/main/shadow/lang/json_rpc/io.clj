(ns shadow.lang.json-rpc.io
  (:require [clojure.data.json :as json]
            [clojure.core.async :as async :refer (go alt!)]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import (java.io EOFException)
           (java.net SocketException)))

(defn read-msg! [in len]
  (let [msg-chars
        (char-array len)]

    (loop [pending len
           pos 0]
      (when (pos? pending)
        (let [read (.read in msg-chars pos pending)]
          (if (= -1 read)
            (throw (EOFException.))
            (recur (- pending len) (+ pos read))
            ))))

    (String. msg-chars)
    ))

(defn stream-reader [is input-chan]
  (let [in (io/reader is)]
    (try
      (loop []
        (when-not (Thread/interrupted)
          (let [header (.readLine in)]
            ;; just exit the loop if it doesn't send what we want
            ;; FIXME: some sort of notification that we exited
            ;; FIXME: spec mentions Content-Type but vscode never seems to send it
            (when (and (seq header)
                       (str/starts-with? header "Content-Length: ")
                       (= "" (.readLine in)))

              (let [len
                    (-> (subs header (count "Content-Length: "))
                        (Long/parseLong))

                    msg-str
                    (read-msg! in len)

                    msg
                    (json/read-str msg-str :key-fn keyword)]

                (prn [:jsonrpc/in msg])

                (if-not (async/offer! input-chan msg)
                  (throw (ex-info "input offer failed!" {:msg msg}))
                  (recur)))))))
      ;; ignore this
      (catch SocketException e
        nil)
      ;; FIXME: ignoring what happened, just assuming the input-stream is dead
      (catch Exception e
        (prn [:stream-reader e])
        nil)
      (finally
        (async/close! input-chan)))))

(defn io-loop
  "expects an input/output stream pair
   loop-stop as an additional signal channel besides input EOF to signal that the loop should end"
  [server-fn is os loop-stop]
  (let [output
        (async/chan 100)

        input
        (async/chan 100)

        out
        (io/writer os)

        ;; FIXME: reads until stream EOF, we must yield the stream when input closes though
        ;; interrupting the thread may not always work
        in-thread
        (doto (Thread. #(stream-reader is input))
          (.start))]

    (go (loop []
          (alt!
            loop-stop
            ([_] ::loop-stop)

            output
            ([msg]
              (when (some? msg)
                (let [json-msg
                      (json/write-str msg)

                      json-len
                      (count json-msg)]

                  (prn [:out msg])

                  (doto out
                    (.write "Content-Length: ")
                    (.write (str json-len))
                    (.write "\r\n")
                    (.write "\r\n")
                    (.write json-msg)
                    (.flush)))
                (recur)))))

        ;; this should lead to an exit in server-fn
        (async/close! input)

        ;; interrupt won't work while the thread is in .read
        ;; when the stream has an underlying socket and thats closed the thread will terminate
        (when (.isAlive in-thread)
          (.interrupt in-thread)))

    (server-fn input output)
    ))