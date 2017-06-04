(ns shadow.cljs.npm.jsonrpc
  (:require-macros [cljs.core.async.macros :refer (go)])
  ;; cursive is driving me crazy when I try to use net alias
  (:require ["net" :as node-net]
            [goog.object :as gobj]
            [cljs.core.async :as async]
            [clojure.string :as str]
            [shadow.json :as json]))

;; couldn't find a single json-rpc client for node that works
;; all the clients I looked at only support method calls
;; not receiving notifications or anything like that

;; json-rpc spec
(def line-sep "\r\n")

;; its fine to hard-code this exactly since I know what the server sends
;; this isn't a general purpose client
(def content-length "Content-Length: ")

(defn client-process
  [state]
  (loop [{:keys [buffer size body? msg-in] :as state} state]
    ;; (prn [:loop-state state])
    (cond
      ;; check if buffer contains enough data to finish message
      (and body? (>= (count buffer) size))
      (let [msg (subs buffer 0 size)]
        (async/offer! msg-in (json/read-str msg {}))
        (-> state
            (update :buffer subs size)
            (assoc :body? false :size 0)))

      ;; in body but not enough data available yet
      body?
      state

      :headers
      (let [line-end (str/index-of buffer line-sep)]
        (if-not line-end
          state ;; not enough data for a line

          (let [line (subs buffer 0 line-end)

                state
                (update state :buffer subs (+ line-end (count line-sep)))]

            (cond
              (= "" line)
              (-> state
                  (assoc :body? true)
                  (recur))

              (str/starts-with? line content-length)
              (let [size
                    (subs line (count content-length))

                    size-int
                    (js/parseInt size 10)]

                (-> state
                    (assoc :size size-int)
                    (recur)))

              :else
              (throw (ex-info (str "client-error, did not expect: " line) state))
              )))))))

(defn read-loop [input msg-in]
  (go (loop [state
             {:msg-in msg-in
              :buffer ""
              :headers {}
              :size 0
              :body? false}]
        (when-some [data (<! input)]
          (-> state
              (update :buffer str (.toString data))
              (client-process)
              (recur)
              )))
      (prn "client loop end")))

(defn connect [{:keys [host port in out] :as config}]
  (let [tcp-in
        (async/chan 10)

        client
        (node-net/connect port host)

        connect
        (async/chan)]

    (.on client "connect"
      (fn [err]
        (if err
          (do (async/close! in)
              (async/close! out)
              (async/close! tcp-in))

          (do (async/put! connect true)
              (go (loop []
                    (when-some [out (<! out)]
                      (let [body
                            (json/write-str out)

                            len
                            (count body)

                            msg
                            (str "Content-Length: " len line-sep
                                 line-sep
                                 body)]

                        ;; (prn [:jsonrpc/out len msg])
                        (.write client msg)
                        (recur))))

                  (.close client))

              (read-loop tcp-in in)
              ))

        (async/close! connect)
        ))

    (.on client "data" #(async/offer! tcp-in %1))
    (.on client "end" #(async/close! tcp-in))

    connect
    ))



