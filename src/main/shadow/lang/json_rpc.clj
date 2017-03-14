(ns shadow.lang.json-rpc
  (:require [clojure.data.json :as json]
            [clojure.core.async :as async]
            [clojure.string :as str])
  (:import (java.net Socket SocketException)
           (java.io OutputStreamWriter BufferedWriter BufferedReader InputStreamReader EOFException)))

(defn write-fn [^Socket socket]
  (let [out
        (-> (.getOutputStream socket)
            (OutputStreamWriter.)
            (BufferedWriter.))]

    (fn [msg]
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
          (.flush))))))


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

(defn client-reader [^Socket s input-chan]
  (let [in
        (-> (.getInputStream s)
            (InputStreamReader.)
            (BufferedReader.))]

    (try
      (loop []
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
                  (json/read-str msg-str)]

              (if-not (async/offer! input-chan msg)
                (throw (ex-info "input offer failed!" {:msg msg}))
                (recur))))))

      (catch SocketException e
        (async/close! input-chan)
        nil))))
