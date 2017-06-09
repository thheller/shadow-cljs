(ns shadow.cljs.devtools.remote.api
  (:require [clojure.core.async :as async :refer (go <! <!! >!! >! alt!)]
            [clojure.edn :as edn]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.config :as config]))

;; API

(defn send-msg [{:keys [msg-out encoding] :as state} response]
  (let [msg
        {:headers {"content-type" encoding}
         :body (pr-str response)}]

    (when-not (async/offer! msg-out msg)
      (prn [:dropped-reply response]))

    state
    ))

(defn notify [state method data]
  (send-msg state {:method method :params data}))

(defn reply-ok [state request result]
  (send-msg state
    {:id (:id request)
     :result result}))

(defn reply-error [state request error-code error-msg error-data]
  (send-msg state
    {:id (:id request)
     :code error-code
     :message error-msg
     :data error-data}))

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
  (reply-error state msg 1001 "didn't understand request" msg)
  state)

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

(defn client [{:keys [system-bus control] :as service} msg-in msg-out]
  (let [init-state
        (assoc service
          :msg-in msg-in
          :msg-out msg-out)

        worker-output
        (-> (async/sliding-buffer 10)
            (async/chan))]

    ;; FIXME: broadcasting this to every client is overkill
    (sys-bus/sub system-bus :worker-output worker-output)

    (go (loop [state init-state]
          (alt!
            control
            ([_] :server-stop)

            msg-in
            ([msg]
              (when (some? msg)
                (-> state
                    (process msg)
                    (recur))))

            worker-output
            ([msg]
              (when (some? msg)
                (-> state
                    (notify "cljs/worker-output" msg)
                    (recur))))
            ))
        )))

(defn start [system-bus]
  (let [control
        (async/chan)]

    {:system-bus system-bus
     :control control}))

(defn stop [{:keys [control]}]
  (async/close! control))

;; IMPL

(defmethod process-rpc "cljs/hello"
  [state {:keys [params] :as msg}]
  (reply-ok state msg (config/load-cljs-edn)))
