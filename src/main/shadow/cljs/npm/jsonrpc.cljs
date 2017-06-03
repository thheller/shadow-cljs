(ns shadow.cljs.npm.jsonrpc
  (:require-macros [cljs.core.async.macros :refer (go)])
  ;; cursive is driving me crazy when I try to use net alias
  (:require ["net" :as node-net]
            [cljs.core.async :as async]
            [clojure.string :as str]))

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
  (loop [{:keys [buffer size body?] :as state} state]
    ;; (prn [:loop-state state])
    (cond
      ;; check if buffer contains enough data to finish message
      (and body? (>= (count buffer) size))
      (let [msg (subs buffer 0 size)]
        (prn [:msg (js/JSON.parse msg)])
        (-> state
            (update :buffer subs size)
            (assoc :body? false :size 0)) )

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

(defn client-loop [input output]
  (go (loop [state
             {:buffer ""
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

(defn connect-fn []
  (this-as client
    (let [input
          (async/chan 10)

          output
          (async/chan 10)]

      (.on client "close"
        (fn [had-error]
          (async/close! input)
          (println "tcp client close" had-error)))

      (.on client "data"
        (fn [data]
          (async/offer! input data)))

      (.on client "end"
        (fn []
          (println "tcp client end")
          (async/close! input)
          ))

      (.on client "error"
        (fn [err]
          (println "tcp client error" err)))

      (go (loop []
            (when-some [out (<! output)]
              (.write client out)
              (recur))))

      (client-loop input output)
      )))

(defn connect [host port]
  (let [client (node-net/connect port host connect-fn)]

    client
    ))



