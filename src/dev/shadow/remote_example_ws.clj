(ns shadow.remote-example-ws
  (:require
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [cognitect.transit :as transit])
  (:import
    [java.io ByteArrayInputStream ByteArrayOutputStream]
    [java.net URI]
    [java.net.http HttpClient WebSocket$Listener]))

;; message handlers, return nil if you want to kill socket
(defmulti handle-msg (fn [state msg] (:op msg)) :default ::default)

(defmethod handle-msg :ping [{:keys [ws-send] :as state} msg]
  (ws-send {:op :pong})
  (assoc state :last-ping (System/currentTimeMillis)))

;; ask relay about clients and notify about changes
(defmethod handle-msg :welcome [{:keys [ws-send] :as state} msg]
  (ws-send {:op :request-clients :notify true})
  (assoc state :client-id (:client-id msg)))

;; first response to :request-clients
(defmethod handle-msg :clients [state msg]
  (prn [:clients msg])
  state)

;; async notification of clients connecting/disconnecting
;; reload shadow-cljs UI for example
(defmethod handle-msg :notify [state msg]
  (prn [:notify (:client-id msg) (:event-op msg) (dissoc msg :op :event-op :client-id)])
  state)

;; websocket got closed
(defmethod handle-msg :close [state {:keys [status reason] :as msg}]
  state)

;; error is the onError exception
(defmethod handle-msg :error [state {:keys [error] :as msg}]
  state)

;; can't receive/send any more messages, chance for state cleanup
(defmethod handle-msg :cleanup [state msg]
  state)

;; unhandled messages
(defmethod handle-msg ::default [state msg]
  (prn [:msg msg])
  state)

(defmethod handle-msg :eval-runtime-error
  [{:keys [ws-send] :as state} {:keys [ex-oid from] :as msg}]
  ;; get us the error
  (ws-send {:op :obj-request :to from :request-op :ex-str :oid ex-oid})
  state)

(defmethod handle-msg :eval-result-ref
  [{:keys [ws-send] :as state} {:keys [ref-oid from] :as msg}]
  ;; just request printed result for every eval
  (ws-send {:op :obj-request :to from :request-op :edn :oid ref-oid})
  state)


(defn connect []
  (let [token ;; needed to connect
        (slurp (io/file ".shadow-cljs" "server.token"))

        port ;; could be an arg
        (Long/parseLong (slurp (io/file ".shadow-cljs" "http.port")))

        uri
        (URI. (str "ws://localhost:" port "/api/remote-relay?server-token=" token))

        client
        (HttpClient/newHttpClient)

        connect-chan
        (async/chan)

        msg-in
        (async/chan 64)

        websocket
        (-> client
            (.newWebSocketBuilder)
            (.buildAsync uri
              (reify WebSocket$Listener
                (onOpen [this socket]
                  (.request socket 1)
                  (async/close! connect-chan))
                (onError [this socket error]
                  (async/>!! msg-in {:op :error :error error}))
                (onText [this socket char-seq last]
                  (let [text (.toString char-seq)
                        r (transit/reader (ByteArrayInputStream. (.getBytes text "UTF-8")) :json)
                        msg (transit/read r)]
                    (async/>!! msg-in msg)
                    (.request socket 1)))
                (onClose [this socket status reason]
                  (async/>!! msg-in {:op :close :status status :reason reason})
                  (async/close! msg-in))
                ))
            (.join))

        ws-send
        (fn [data]
          (let [out (ByteArrayOutputStream. 4096)
                w (transit/writer out :json)]
            (transit/write w data)
            (let [text (.toString out "UTF-8")]
              (-> (.sendText websocket text true)
                  (.join))
              ;; don't want to return the socket here
              true)))]

    ;; run main logic in its own thread
    (async/thread
      ;; wait for websocket onOpen finish
      ;; FIXME: handle connect failures in a way that is noticeable
      (when (async/alt!! connect-chan ([_] true) (async/timeout 5000) ([_] false))
        ;; say hello, relay will send :welcome or kick us
        (ws-send {:op :hello :client-info {:type :remote-test}})

        ;; main message loop
        (let [last-state
              (loop [state {:ws-send ws-send
                            :client client
                            :websocket websocket
                            :msg-in msg-in}]
                (if-some [msg (async/<!! msg-in)]
                  (if-some [next-state (handle-msg state msg)]
                    (recur next-state)
                    state)
                  state))]

          ;; leave chance for common cleanup
          (handle-msg last-state {:op :cleanup}))

        ;; just in case it is still open
        (.abort websocket)))

    {:msg-in msg-in :ws-send ws-send :ws websocket}))

(comment
  ;; dummy interaction
  ;; FIXME: could be cleaner if adding some rpc mechanism
  (def x (connect))

  ;; send bad message to relay, will result in :unknown-relay-op
  ((:ws-send x) {:op :foo})

  ;; eval results in :eval-result-ref, which just gives the :ref-oid
  ;; above the handle-msg :eval-result-ref then requests it to be printed as :edn
  ;; 1 is the default CLJ runtime
  ((:ws-send x) {:op :clj-eval :to 1 :input {:code "::foo" :ns 'user}})


  (async/close! (:msg-in x)))