(ns shadow.lang.server
  (:require [clojure.core.async :as async :refer (go >! <! alt!! alt! <!! >!!)]
            [shadow.lang.protocol :as p]

    ;; FIXME: this is basically defining the protocol, not sure this is the best place to do this
    ;; but don't remove these or otherwise the server won't do anything
            [shadow.lang.protocol.connection]
            [shadow.lang.protocol.text-document]))

(defn process-client
  [client-state msg]
  (prn [:process-client msg])
  client-state)

(defn do-call
  [{:keys [result-chan] :as client-state} {:keys [id method params] :as msg}]
  (let [{::p/keys [result-type] :as call-result}
        (p/handle-call client-state method params)]

    ;; dispatch to result-chan instead of writing directly to the socket
    ;; the request may have been cancelled while the call was running
    ;; this way we won't send responses the client is no longer interested in
    ;; this doesn't guarantee that the client-loop will process the cancelRequest input before result-chan
    (case result-type
      :ok
      (do (>!! result-chan {:id id :result call-result})
          (:next-state call-result))

      :error
      (do (>!! result-chan {:id id :result call-result})
          (:next-state call-result))

      :defer
      (let [{:keys [next-state chan]} call-result]
        (go (let [result (<! chan)]
              (>! (:result-chan client-state) {:id id :result result})))
        (update next-state :pending assoc id chan))

      (throw (ex-info "invalid call result" {:result-type result-type :msg msg :result call-result})))
    ))

(defn do-cast [client-state {:keys [method params] :as msg}]
  (p/handle-cast client-state method params))

(defn process-input
  [client-state {:keys [id method params] :as msg}]
  (prn [:in method id params])
  ;; spec says everything with an id is a request otherwise a notification
  (if (contains? msg :id)
    (do-call client-state msg)
    (do-cast client-state msg)))

(defn write-result
  [output id {::p/keys [result-type] :as call-result}]

  (case result-type
    :ok
    (>!! output {"id" id "result" (:result call-result)})

    :error
    (let [{:keys [error-code error-msg error-data]} call-result]
      (>!! output
        {"id" id
         "error"
         (-> {"code" error-code
              "error" error-msg}
             (cond->
               error-data
               (assoc "data" error-data)))}))

    (throw (ex-info "invalid call result" {:result-type result-type :result call-result}))))

(defn server-loop
  [system input output]
  (let [control
        (async/chan 10)

        ;; results go here and are forwarded to ouput unless cancelled
        result-chan
        (async/chan 100)

        ;; server->client notifications go here
        notify-chan
        (async/chan 100)]

    (loop [client-state
           (p/client-reset
             {:system system
              :control control
              :result-chan result-chan
              :notify-chan notify-chan})]

      ;; FIXME: if any of these channels is closed the loop will exit
      ;; input can be closed by the system (if the socket dies)
      ;; everything else should probably not be closed
      (alt!!
        control
        ([msg]
          (when-not (nil? msg)
            (recur (process-client client-state msg))))

        result-chan
        ([msg]
          (when-not (nil? msg)
            (let [{:keys [id result]} msg]
              (if (contains? (:cancelled client-state) id)
                (-> client-state
                    (update :cancelled disj id)
                    (update :pending dissoc id)
                    (recur))
                (do (write-result output id result)
                    (-> client-state
                        (update :pending dissoc id)
                        (recur)))
                ))))

        notify-chan
        ([msg]
          (when-not (nil? msg)
            (>!! output msg)
            (recur client-state)))

        input
        ([msg]
          (when-not (nil? msg)
            (let [next-state
                  (try
                    (process-input client-state msg)
                    (catch Exception e
                      (prn [:failed-to-process-client-msg msg e])
                      client-state))]
              (recur next-state))))))

    (async/close! notify-chan)
    (async/close! control)
    ))


