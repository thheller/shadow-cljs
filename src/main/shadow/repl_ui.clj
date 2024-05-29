(ns shadow.repl-ui
  (:require
    [clojure.tools.reader.reader-types :as readers]
    [clojure.tools.reader :as reader]
    [clojure.core.async :as async]
    [clojure.java.io :as io]
    [shadow.cljs :as-alias m]
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

(defmethod handle-msg :welcome [{:keys [ws-send] :as state} msg]
  (prn [:welcome msg])
  (assoc state :client-id (:client-id msg)))

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

        msg-out
        (async/chan 64)

        connected-ref
        (atom false)

        websocket
        (-> client
            (.newWebSocketBuilder)
            (.buildAsync uri
              (reify WebSocket$Listener
                (onOpen [this socket]
                  (.request socket 1)
                  (prn [:connected])
                  (reset! connected-ref true)
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
                  (reset! connected-ref false)
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
        (ws-send {:op :hello :client-info {:type ::remote}})

        ;; main message loop
        (let [last-state
              (loop [state {:ws-send ws-send
                            :client client
                            :websocket websocket
                            :msg-in msg-in}]
                (async/alt!!
                  msg-in
                  ([msg]
                   (if-not msg
                     state
                     (if-some [next-state (handle-msg state msg)]
                       (recur next-state)
                       state)))

                  msg-out
                  ([msg]
                   (if-not msg
                     state
                     (do (ws-send msg)
                         (recur state))))))]

          ;; leave chance for common cleanup
          (handle-msg last-state {:op :cleanup}))

        ;; just in case it is still open
        (.abort websocket)))

    {:msg-in msg-in :msg-out msg-out :ws websocket :connected-ref connected-ref}))

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


;; exact copy from shadow.cljs.repl
;; copied here so that nothing else of shadow-cljs is required to be loaded
;; just to save some startup time
(defn dummy-read-one
  "dummy read one form from a stream, only meant to get the string form
   cannot perform actual read since it doesn't know current ns or aliases
   only meant to be used when forced to read from a stream but wanting a string"
  [reader]
  (try
    (let [in
          (readers/source-logging-push-back-reader reader)

          eof-sentinel
          (Object.)

          reader-opts
          {:eof eof-sentinel
           :read-cond :preserve}

          [form source]
          (binding [*ns* (find-ns 'user)
                    reader/*data-readers* {}
                    reader/*default-data-reader-fn* (fn [tag val] val)
                    reader/resolve-symbol identity
                    ;; used by tools.reader to resolve ::foo/kw
                    ;; we don't actually care, we just want the original source
                    ;; just calls (*alias-map* sym) so a function is fine
                    reader/*alias-map* (fn [sym] sym)]

            (reader/read+string reader-opts in))

          eof?
          (identical? form eof-sentinel)]

      (-> {:eof? eof?}
          (cond->
            (not eof?)
            (assoc :source source))))
    (catch Exception ex
      {:error? true
       :ex ex})))

(defn -main [& args]
  (try
    (let [stream-id :default ;; FIXME: get from args
          {:keys [msg-out connected-ref] :as conn} (connect)]

      (add-watch connected-ref ::foo
        (fn [_ _ oval nval]
          (when-not nval
            ;; FIXME: is likely in blocking *in* read, should kill that and terminate or reconnect
            (println "WS disconnected!"))))

      (async/>!! msg-out {:op ::m/repl-stream-start! :to 1
                          :stream-id stream-id
                          :target 1
                          :target-ns 'shadow.user
                          :target-op :clj-eval})
      (loop []
        (let [val (dummy-read-one *in*)]
          (when-not (:eof? val)
            (async/>!! msg-out {:op ::m/repl-stream-input! :to 1 :stream-id stream-id :code (:source val)})
            ;; FIXME: this is totally dumb
            ;; there is a timing issue when reading multiple things from one "input"
            ;; e.g. input: 1 2 3<enter>
            ;; these are 3 separate evals, but since this only reads and never waits for any other completion
            ;; it can lead to the remote side accepting the message without having finished processing the first
            ;; so this is meant to sort of throttle how fast inputs arrive, so they at least are added in order
            ;; really want to avoid having the server reply ack "ready for next" before proceeding
            ;; the server should still ensure eval happens in order and not parallel
            (Thread/sleep 10)
            (when @connected-ref
              (recur)))))

      (async/close! msg-out))

    (catch java.util.concurrent.CompletionException e
      (if (instance? java.net.ConnectException (.getCause e))
        (do (println "Connect failed. Is shadow-cljs running?")
            (println "This utility only connects to the local shadow-cljs instance, it does not start one."))
        (.printStackTrace e)))
    (catch Exception e
      ;; bypass the default repl report-error garbage
      (.printStackTrace e))))

(comment
  :yo

  ::foo

  )