(ns shadow.cljs.devtools.server.repl-system.clojure
  (:require [clojure.main :as cm]
            [clojure.core.async :as async :refer (<!!)]
            [shadow.jvm-log :as log]
            [shadow.cljs.model :as m])
  (:import [java.io Writer PipedInputStream PipedOutputStream InputStreamReader StringWriter StringReader]
           [clojure.lang LineNumberingPushbackReader]
           [java.util UUID]))


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

              :prompt (fn [])

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

(defmethod process-clj-msg* ::m/runtime-eval
  [{:keys [runtime-id sys-out] :as state}
   {::m/keys [tool-id input-text ns] :as msg}]
  (let [eof
        (Object.)

        in
        (-> (StringReader. input-text)
            (LineNumberingPushbackReader.))

        send-msg
        (fn [msg]
          (let [msg (assoc msg
                      ::m/tool-id tool-id
                      ::m/runtime-id runtime-id)]
            (when-not (async/offer! sys-out msg)
              (log/warn ::clj-runtime-overload msg))))]

    (binding [*ns* (find-ns ns)]
      (loop []
        (let [[val form] (read+string in false eof)]
          (when-not (identical? val eof)
            (let [result (eval val)
                  result-id (str (UUID/randomUUID))
                  printed (pr-str result)]

              (send-msg {::m/op ::m/runtime-result
                         ::m/form form
                         ::m/printed-result printed
                         ::m/result-id result-id})

              (recur)))))))
  state)

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
