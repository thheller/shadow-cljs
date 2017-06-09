(ns shadow.cljs.devtools.remote.api
  (:require [clojure.core.async :as async :refer (go <! <!! >!! >! alt!)]
            [clojure.edn :as edn]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.server.supervisor :as super]))

;; API

(defn send-msg [{:keys [client-out encoding] :as state} response]
  (let [msg
        {:headers {"content-type" encoding}
         :body (pr-str response)}]

    (when-not (async/offer! client-out msg)
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

(defn client [{:keys [system-bus control] :as service} client-in client-out]
  (let [init-state
        (assoc service
          :client-in client-in
          :client-out client-out)

        super-updates
        (-> (async/sliding-buffer 1)
            (async/chan))]

    (sys-bus/sub system-bus ::super/update super-updates)

    (go (loop [state init-state]
          (alt!
            control
            ([_] :server-stop)

            super-updates
            ([update]
              (when (some? update)
                (-> state
                    (notify "supervisor/update" update)
                    (recur))))

            client-in
            ([msg]
              (when (some? msg)
                (-> state
                    (process msg)
                    (recur))))
            ))
        )))

(defn start [system-bus supervisor]
  (let [control
        (async/chan)]

    {:system-bus system-bus
     :supervisor supervisor
     :control control}))

(defn stop [{:keys [control]}]
  (async/close! control))

;; IMPL

(defmethod process-rpc "cljs/hello"
  [{:keys [state-ref supervisor] :as state} {:keys [params] :as msg}]
  (reply-ok state msg
    {:config
     (config/load-cljs-edn)

     :supervisor
     (super/get-status supervisor)}))
