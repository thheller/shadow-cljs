(ns shadow.cljs.devtools.server.repl-system
  (:require
    [clojure.core.async :as async :refer (go >! <! >!! <!!)]
    [clojure.main :as cm]
    [shadow.cljs.model :as m]
    [shadow.jvm-log :as log])
  (:import [java.util UUID Date]
           [clojure.lang LineNumberingPushbackReader]
           [java.io PipedInputStream PipedOutputStream BufferedReader InputStreamReader Writer OutputStreamWriter BufferedWriter StringWriter]))

(defn send-to-tools [state-ref msg]
  (doseq [{:keys [tool-out]} (-> @state-ref :tools vals)]
    (>!! tool-out msg)))

(defn send-to-runtimes [state-ref msg]
  (doseq [{:keys [runtime-in] :as info} (-> @state-ref :runtimes vals)]
    (>!! runtime-in msg)))

(defn handle-runtime-msg [state-ref {:keys [runtime-id] :as runtime-data} {::m/keys [tool-id] :as msg}]
  (if-not tool-id
    ;; broadcast to all connected tools
    (send-to-tools state-ref (assoc msg ::runtime-id runtime-id))

    ;; only send to specific tool
    (let [{:keys [tool-out] :as tool}
          (get-in @state-ref [:tools tool-id])]

      (if-not tool
        (log/debug ::tool-gone msg)

        (>!! tool-out (-> msg
                          (dissoc ::m/tool-id)
                          (assoc ::m/runtime-id runtime-id))))
      )))

(defn handle-tool-msg [state-ref {:keys [tool-id tool-out] :as tool-data} {::m/keys [request-id runtime-id] :as msg}]
  ;; (log/debug ::handle-tool-msg msg)
  (if-not runtime-id
    ;; client didn't send runtime. handle system ops later
    (log/debug ::handle-tool-msg-noop msg)

    ;; client did send ::m/runtime-id, forward to runtime if found
    (let [{:keys [runtime-in] :as runtime-info}
          (get-in @state-ref [:runtimes runtime-id])]
      (if-not runtime-info
        (>!! tool-out {::m/error ::m/runtime-not-found
                       ::m/request-id request-id
                       ::m/runtime-id runtime-id})

        ;; forward with tool-id only, replies should be coming from runtime-out
        (>!! runtime-in (assoc msg ::m/tool-id tool-id))
        ))))

(defn runtime-connect
  "JS runtime connected and ready to answer queries

   runtime-out is messages coming from runtime (either for tools or this)
   return runtime-in for messages coming from tools forwarded to the runtime

   runtime-out closing means the runtime went away"
  [svc runtime-id runtime-info runtime-out]

  (let [{:keys [state-ref]} svc

        runtime-in
        (async/chan 10)

        runtime-info
        (assoc runtime-info :since (Date.))

        runtime-data
        {:runtime-id runtime-id
         :runtime-info runtime-info
         :runtime-in runtime-in
         :runtime-out runtime-out}]

    (swap! state-ref assoc-in [:runtimes runtime-id] runtime-data)

    (send-to-tools state-ref {::m/op ::m/runtime-connect
                              ::m/runtime-id runtime-id
                              ::m/runtime-info runtime-info})

    (go (loop []
          (when-some [msg (<! runtime-out)]
            (handle-runtime-msg state-ref runtime-data msg)
            (recur)
            ))

        (send-to-tools state-ref {::m/op ::m/runtime-disconnect
                                  ::m/runtime-id runtime-id})

        (swap! state-ref update :runtimes dissoc runtime-id))

    runtime-in
    ))

(defn tool-connect
  [svc tool-id tool-in]
  "tool-in is messages coming from tools (potentially forwarded to runtimes)
   returns tool-out for messages to the connected tool

   tool-in closing means tool disconnected
   tool-out closing means its connection should end (eg. system shutdown)"

  (let [{:keys [state-ref]} svc

        tool-out
        (async/chan 10)

        tool-data
        {:tool-id tool-id
         :tool-in tool-in
         :tool-out tool-out}]

    (swap! state-ref assoc-in [:tools tool-id] tool-data)

    (go (loop []
          (when-some [msg (<! tool-in)]
            (handle-tool-msg state-ref tool-data msg)
            (recur)))

        ;; send to all runtimes so they can cleanup state?
        (send-to-runtimes state-ref {::m/op ::m/tool-disconnect
                                     ::m/tool-id tool-id})

        (swap! state-ref update :tools dissoc tool-id)
        (async/close! tool-out))

    tool-out))

(defmulti process-clj-msg* (fn [state msg] (::m/op msg)))

(defmethod process-clj-msg* :default [state msg]
  (log/debug ::unknown-clj-msg msg)
  state)

(defmethod process-clj-msg* ::m/tool-disconnect [state {::m/keys [tool-id] :as msg}]
  (update state :repl-sessions
    (fn [s]
      (reduce-kv
        (fn [s session-id {:keys [pipe-out] :as session}]
          (if (not= tool-id (:tool-id session))
            s
            (do (log/debug ::tool-close-session msg)
                (.close pipe-out)
                (dissoc s session-id)
                )))
        s
        s))))


;; taken from clojure/core/server.clj 1.10 alphas PrintWriter_on
;; renamed to prevent name collision

(defn ^java.io.PrintWriter writer->fn
  "implements java.io.PrintWriter given flush-fn, which will be called
  when .flush() is called, with a string built up since the last call to .flush().
  if not nil, close-fn will be called with no arguments when .close is called"
  {:added "1.10"}
  [flush-fn close-fn]
  (let [sb (StringBuilder.)]
    (-> (proxy [Writer] []
          (flush []
            (when (pos? (.length sb))
              (flush-fn (.toString sb)))
            (.setLength sb 0))
          (close []
            (.flush ^Writer this)
            (when close-fn (close-fn))
            nil)
          (write [str-cbuf off len]
            (when (pos? len)
              (if (instance? String str-cbuf)
                (.append sb ^String str-cbuf ^int off ^int len)
                (.append sb ^chars str-cbuf ^int off ^int len)))))
        java.io.BufferedWriter.
        java.io.PrintWriter.)))

(defmethod process-clj-msg* ::m/session-start
  [{:keys [runtime-id sys-out] :as state} {::m/keys [session-id tool-id] :as msg}]

  (let [pipe-out
        (PipedOutputStream.)

        session-in
        (-> (PipedInputStream. pipe-out)
            (InputStreamReader.)
            (LineNumberingPushbackReader.))

        send-msg
        (fn [msg]
          (let [msg (assoc msg
                      ::m/tool-id tool-id
                      ::m/runtime-id runtime-id
                      ::m/session-id session-id)]
            (when-not (async/offer! sys-out msg)
              (log/warn ::clj-session-overload msg))))

        session-out
        (writer->fn
          (fn [text]
            (send-msg {::m/op ::m/session-out
                       ::m/session-out text}))
          nil)

        session-err
        (writer->fn
          (fn [text]
            (send-msg {::m/op ::m/session-err
                       ::m/session-err text}))
          nil)

        session-ns-ref
        (atom 'user)

        thread-fn
        (bound-fn []
          (binding [*in* session-in
                    *out* session-out
                    *err* session-err]
            (cm/repl
              :need-prompt
              (constantly false)

              :print
              (fn [val]
                (let [result-id (str (UUID/randomUUID))
                      printed (pr-str val)]
                  ;; FIXME: store val for later
                  (send-msg {::m/op ::m/session-result
                             ::m/printed-result printed
                             ::m/result-id result-id}))

                (let [ns (symbol (str *ns*))]
                  (when (not= ns @session-ns-ref)
                    (send-msg {::m/op ::m/session-update
                               ::m/session-ns ns})
                    (reset! session-ns-ref ns)
                    )))

              :caught
              (fn [ex]
                (log/debug-ex ex ::clj-session-ex {:session-id session-id})
                (let [sw (StringWriter.)]
                  (binding [*err* sw]
                    (cm/repl-caught ex))

                  (send-msg {::m/op ::m/session-err
                             ::m/session-err (.toString sw)}))
                )))

          (send-msg {::m/op ::m/session-end}))

        session-thread
        (Thread. thread-fn (str "shadow-clj-repl-" runtime-id))]

    (send-msg {::m/op ::m/session-started
               ;; FIXME: the loop should probably send this
               ::m/session-ns 'user})

    (.start session-thread)

    (assoc-in state [:repl-sessions session-id]
      {:tool-id tool-id
       :session-id session-id
       :session-in session-in
       :session-out session-out
       :session-err session-err
       :session-thread session-thread
       :pipe-out pipe-out})))

(defmethod process-clj-msg* ::m/session-eval
  [state {::m/keys [session-id input-text] :as msg}]
  (let [session (get-in state [:repl-sessions session-id])]
    (if-not session
      (do (log/warn ::session-not-found msg)
          state)
      (let [{:keys [pipe-out]} session]

        (.write pipe-out (.getBytes (str input-text "\n")))
        (.flush pipe-out)
        (log/debug ::session-eval {:pipe-out pipe-out :text input-text})

        state
        ))))

(defn process-clj-msg [state msg]
  (log/debug ::process-clj-msg msg)
  (try
    (process-clj-msg* state msg)
    (catch Exception ex
      (log/warn-ex ex ::process-clj-msg msg)
      state)))

(defn clj-loop! [svc runtime-id sys-in sys-out]
  (loop [state {:repl-system svc
                :runtime-id runtime-id
                :sys-in sys-in
                :sys-out sys-out
                :repl-sessions {}}]
    (when-some [msg (<!! sys-in)]
      (-> state
          (process-clj-msg msg)
          (recur)
          )))

  (async/close! sys-out))

(defn start []
  (let [svc
        {::service true
         :state-ref (atom {:tools {}
                           :runtimes {}})}

        clj-out
        (async/chan 1000)

        clj-id
        (str (UUID/randomUUID))

        clj-in
        (runtime-connect
          svc
          clj-id
          {:lang :clj
           :build-id :none
           :runtime-type :clj}
          clj-out)]

    (async/thread (clj-loop! svc clj-id clj-in clj-out))
    svc
    ))

(defn stop [{:keys [state-ref] :as svc}]
  (let [{:keys [tools]} @state-ref]

    (doseq [{:keys [tool-out]} (vals tools)]
      (async/close! tool-out))))
