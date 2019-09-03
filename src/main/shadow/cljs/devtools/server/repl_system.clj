(ns shadow.cljs.devtools.server.repl-system
  (:require
    [clojure.core.async :as async :refer (go >! <! >!! <!!)]
    [shadow.cljs.devtools.server.repl-system.clojure :as clojure]
    [shadow.cljs.model :as m]
    [shadow.jvm-log :as log])
  (:import [java.util UUID Date]))

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

(defmulti handle-sys-msg (fn [state-ref tool-data msg] (::m/op msg ::default)) :default ::default)

(defmethod handle-sys-msg ::default [state-ref tool-data msg]
  (log/debug ::handle-tool-msg-noop msg))

(defmethod handle-sys-msg ::m/runtimes
  [state-ref
   {:keys [tool-out] :as tool-data}
   {::m/keys [request-id] :as msg}]
  (let [{:keys [runtimes]} @state-ref
        result (reduce-kv
                 (fn [v runtime-id {:keys [runtime-info]}]
                   (conj v {::m/runtime-id runtime-id
                            ::m/runtime-info runtime-info}))
                 []
                 runtimes)]
    (>!! tool-out {::m/op ::m/runtimes-result
                   ::m/request-id request-id
                   ::m/runtimes result})))

(defn handle-tool-msg [state-ref {:keys [tool-id tool-out] :as tool-data} {::m/keys [request-id runtime-id] :as msg}]
  ;; (log/debug ::handle-tool-msg msg)
  (if-not runtime-id
    ;; client didn't send runtime. tread at system op
    (handle-sys-msg state-ref tool-data msg)

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

    (async/thread (clojure/clj-loop! svc clj-id clj-in clj-out))
    svc
    ))

(defn stop [{:keys [state-ref] :as svc}]
  (let [{:keys [tools]} @state-ref]

    (doseq [{:keys [tool-out]} (vals tools)]
      (async/close! tool-out))))


(comment
  (def svc (start))

  svc
  (stop svc)

  (def svc (:repl-system @shadow.cljs.devtools.server.runtime/instance-ref))

  (def clj-runtime-id (-> svc :state-ref deref :runtimes keys first))

  (def tool-in (async/chan))
  (def tool-out (tool-connect svc 1 tool-in))

  (go (loop []
        (when-some [msg (<! tool-out)]
          (clojure.pprint/pprint msg)
          (recur)))
      (prn ::loop-shutdown))

  (>!! tool-out :foo)

  (>!! tool-in {::m/op ::m/runtimes})

  ;; runtime eval just evals in specified ns
  ;; does not maintain set of bindings (only for length of input-text)
  ;; can eval multiple forms at once, will eof at end of string
  ;; intended to be used by tools that want to eval
  ;; events originating from the user should use session
  (>!! tool-in {::m/op ::m/runtime-eval
                ::m/runtime-id clj-runtime-id
                ::m/input-text "::foo `foo"
                ::m/ns 'user})

  ;; start new session (fresh set of bindings, thread for clojure)
  (>!! tool-in {::m/op ::m/session-start
                ::m/runtime-id clj-runtime-id
                ::m/session-id 1})

  ;; eval in session
  (>!! tool-in {::m/op ::m/session-eval
                ::m/runtime-id clj-runtime-id
                ::m/session-id 1
                ::m/input-text "(ns foo.bar)"})

  (>!! tool-in {::m/op ::m/session-eval
                ::m/runtime-id clj-runtime-id
                ::m/session-id 1
                ::m/input-text "::foo"})

  ;; second session not affected by ns form of first
  (>!! tool-in {::m/op ::m/session-start
                ::m/runtime-id clj-runtime-id
                ::m/session-id 2})

  (>!! tool-in {::m/op ::m/session-eval
                ::m/runtime-id clj-runtime-id
                ::m/session-id 2
                ::m/input-text "::foo"})

  ;; should it even be allowed to "chunk" output? or just assume a full form is sent?
  (>!! tool-in {::m/op ::m/session-eval
                ::m/runtime-id clj-runtime-id
                ::m/session-id 1
                ::m/input-text "(+ 1"})

  (>!! tool-in {::m/op ::m/session-eval
                ::m/runtime-id clj-runtime-id
                ::m/session-id 1
                ::m/input-text "1)"})

  )