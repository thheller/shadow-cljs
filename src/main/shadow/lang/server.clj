(ns shadow.lang.server
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.core.async :as async :refer (go >! <! alt!! <!! >!!)]
            [shadow.lang.json-rpc :as jrpc]
            [shadow.lang.protocol :as p]
            [shadow.lang.protocol.text-document])
  (:import (java.net ServerSocket SocketException Socket)))

(defn InitializeResult []
  {"capabilities"
   ;; ServerCapabilities
   {"textDocumentSync"
    2
    ;; doesn't seem to accept detailed options
    #_{"openClose" true
       "change" 1 ;; 0 = off, 1 = full, 2 = incremental
       "willSave" false
       "willSaveWaitUntil" false
       "save"
       {"includeText" true}}

    "hoverProvider"
    false

    "documentHighlightProvider"
    false

    "documentSymbolProvider"
    false

    "workspaceSymbolProvider"
    false

    "completionProvider"
    ;; CompletionOptions
    {"resolveProvider" true
     "triggerCharacters" ["("]}}})

(defmethod p/handle-call "initialize"
  [client-state method params]
  (let [{:strs [processId capabilities]} params]
    (-> client-state
        (assoc :process-id processId
          :capabilities capabilities)
        (p/call-ok (InitializeResult)))))

(defmethod p/handle-cast "$/cancelRequest"
  [client-state method {:strs [id] :as params}]
  (update client-state :cancelled conj id))

(defn process-client
  [client-state msg]
  (prn [:process-client msg])
  client-state)

(defn do-call
  [{:keys [result-chan] :as client-state} {:strs [id method params] :as msg}]
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

(defn do-cast [client-state {:strs [method params] :as msg}]
  (p/handle-cast client-state method params))

(defn process-input
  [client-state {:strs [id method params] :as msg}]
  (prn [:in method id])
  ;; spec says everything with an idea is a request otherwise a notification
  (if (contains? msg "id")
    (do-call client-state msg)
    (do-cast client-state msg)))

(defn write-result
  [write-fn
   id
   {::p/keys [result-type] :as call-result}]

  (case result-type
    :ok
    (write-fn {"id" id "result" (:result call-result)})

    :error
    (let [{:keys [error-code error-msg error-data]} call-result]
      (write-fn
        {"id" id
         "error"
         (-> {"code" error-code
              "error" error-msg}
             (cond->
               error-data
               (assoc "data" error-data)))}))

    (throw (ex-info "invalid call result" {:result-type result-type :result call-result}))))

(defn client-loop [^Socket socket server-stop]
  (let [control
        (async/chan 10)

        input
        (async/chan 100)

        ;; async results go here
        result-chan
        (async/chan 100)

        ;; server->client notifications go here
        notify-chan
        (async/chan 100)

        write-fn
        (jrpc/write-fn socket)]

    ;; reads until the socket is closed
    (doto (Thread. #(jrpc/client-reader socket input))
      (.start))

    (loop [client-state
           (p/client-reset
             {:control control
              :result-chan result-chan
              :notify-chan notify-chan})]
      (alt!!
        server-stop
        ([_]
          ;; FIXME: do any necessary cleanup, cancel request is possible
          ::server-stop)

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
                (do (write-result write-fn id result)
                    (-> client-state
                        (update :pending dissoc id)
                        (recur)))
                ))))

        notify-chan
        ([msg]
          (when-not (nil? msg)
            (write-fn msg)
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

    (async/close! input)
    (async/close! notify-chan)
    (.close socket)
    ))

(defn accept-loop [server-socket server-stop]
  (loop []
    (when-let [socket
               (try
                 (.accept server-socket)
                 (catch SocketException e nil))]
      (doto (Thread. #(client-loop socket server-stop))
        (.start))
      (recur))))

(defn start []
  (let [ss
        (ServerSocket. 8201)

        server-stop
        (async/chan)

        accept-thread
        (doto (Thread. #(accept-loop ss server-stop))
          (.start))]

    {:server-socket ss
     :server-stop server-stop
     :accept-thread accept-thread}
    ))

(defn stop [{:keys [server-stop server-socket] :as svc}]
  (async/close! server-stop)
  (.close server-socket))

(comment
  (def x (start))

  (stop x)
  )


