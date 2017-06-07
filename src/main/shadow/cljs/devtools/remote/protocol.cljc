(ns shadow.cljs.devtools.remote.protocol
  #?@(:cljs
      [(:require-macros
         [cljs.core.async.macros :refer (go)])
       (:require
         [cljs.core.async :as async]
         [clojure.string :as str])]
      :clj
      [;; dammit cursive
       (:require
         [clojure.core.async :as async :refer (go <!)]
         [clojure.string :as str])]
      ))

(defn parse-int [s]
  #?(:cljs (js/parseInt s 10)
     :clj  (Long/parseLong s)))

;; couldn't find a single json-rpc client for node that works
;; all the clients I looked at only support method calls
;; not receiving notifications or anything like that

;; json-rpc spec
(def line-sep "\r\n")

(defn process
  [state]
  (loop [{:keys [buffer size body? headers output] :as state} state]
    ;; (prn [:loop-state state])
    (cond
      ;; check if buffer contains enough data to finish message
      (and body? (>= (count buffer) size))
      (let [body
            (subs buffer 0 size)

            msg
            {:headers headers
             :body body}]

        (when-not (async/offer! output msg)
          (prn [:dropped-msg msg]))

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
              (let [cl (get headers "content-length")]
                (when-not cl
                  (throw (ex-info "header without content-length" {})))

                (-> state
                    (assoc :body? true :size (parse-int cl))
                    (recur)))

              :else
              (let [idx (str/index-of line ": ")]
                (when-not idx
                  (throw (ex-info "invalid line" {:line line})))

                (let [key (subs line 0 idx)
                      value (subs line (+ idx 2))]

                  (-> state
                      (update :headers assoc (str/lower-case key) value)
                      (recur)))))))))))

(defn read-loop
  "input chan should receive strings of protocol chunks
   output will get partially parsed {:headers {str str} :body str}"
  [input output]
  (go (loop [state
             {:output output
              :buffer ""
              :headers {}
              :size 0
              :body? false}]

        (when-some [data (<! input)]
          (-> state
              (update :buffer str data)
              (process)
              (recur)
              )))))

(defn msg->text [{:keys [headers body]}]
  (let [len
        (count body)

        headers
        (assoc headers "content-length" (str len))]

    (str (->> headers
              (map (fn [[k v]]
                     (str k ": " v)))
              (str/join line-sep))
         line-sep
         line-sep
         body)))

(defn write-loop
  "output must be be {:headers {str str} :body str}"
  [output write-fn]
  (go (loop []
        (when-some [msg (<! output)]
          (if-not (and (map? msg) (:headers msg) (:body msg))
            (do (prn [:invalid-msg msg]))
            (-> msg
                (msg->text)
                (write-fn)))
          (recur)))))
