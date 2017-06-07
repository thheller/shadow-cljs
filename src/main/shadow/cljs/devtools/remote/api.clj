(ns shadow.cljs.devtools.remote.api
  (:require [clojure.core.async :as async :refer (go <! <!! >!! >! alt!)]
            [clojure.edn :as edn]))


(defn reply-ok [{:keys [msg-out encoding] :as state} request result]
  (let [response
        {:id (:id request)
         :result result}

        msg
        {:headers {"content-type" encoding}
         :body (pr-str response)}]

    (when-not (async/offer! msg-out msg)
      (prn [:dropped-reply request response]))

    state
    ))


(defmulti process-rpc
  (fn [state msg]
    (:method msg))
  :default ::default)

(defmulti process-notify
  (fn [state msg]
    (:method msg))
  :default ::default)

(defmethod process-rpc ::default [state msg]
  (prn [:ignored-rpc (:method msg)])
  state)

(defmethod process-rpc "cljs/hello"
  [state {:keys [params] :as msg}]
  (reply-ok state msg {:hello "world"}))

(defmethod process-notify ::default [state msg]
  (prn [:ignored-notify (:method msg)])
  state)

(defn process
  [{:keys [encoding] :as state}
   {:keys [headers body] :as in}]
  (let [content-type
        (get headers "content-type")

        {:keys [method params id] :as msg}
        (edn/read-string body)

        state
        (assoc state :encoding content-type)]

    (cond
      (and (string? method) (some? id) params)
      (process-rpc state msg)

      (and (string? method) params)
      (process-notify state msg)

      :else
      (do (prn [:dropped-msg in])
          state))))

(defn client [x msg-in msg-out]
  (let [init-state
        {:msg-in msg-in
         :msg-out msg-out}]

    (go (loop [state init-state]
          (when-some [msg (<! msg-in)]
            (-> state
                (process msg)
                (recur))
            )))))

(defn start []
  :x)

(defn stop [x])




