(ns shadow.cljs.devtools.server.nrepl-bridge
  (:require
    [clojure.string :as str]
    [clojure.core.async :as async :refer (>!! <!!)]
    [nrepl.transport :as transport]
    [shadow.remote.relay.api :as relay]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.server.supervisor :as supervisor]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.build.warnings :as warnings]
    [clojure.edn :as edn])
  (:import [java.util UUID]
           [java.net SocketException]))

;; since we can't have any kind of nrepl lifecycle we need a service to do this
;; can only assume the presence of the nrepl middleware, not that we actually started
;; the nrepl server. impossible to track if nrepl connection is still active or not,
;; must wait for a message send to fail.

;; should keep the least amount of state possible for any kind of nrepl operation
;; since we can't reliably clean it up

(defn new-call-id []
  (str (UUID/randomUUID)))

(defn nrepl-out [{:keys [transport session id] :as req} {:keys [status] :as msg}]
  (let [res
        (-> msg
            (cond->
              id
              (assoc :id id)
              session
              (assoc :session (-> session meta :id))
              (and (some? status)
                   (not (coll? status)))
              (assoc :status #{status})))]

    (log/debug ::send res)
    (try
      (transport/send transport res)
      (catch Exception ex
        ;; just unconditionally reset the session back to CLJ
        ;; sends most likely fail because of closed sockets
        ;; so this really doesn't matter, just want to avoid getting here too many times
        ((::reset-session req))
        (log/debug-ex ex ::nrepl-out-failed msg)))))

(defn bridge-session
  [{:keys [state-ref] :as svc}
   worker
   {::keys [build-id] :keys [session] :as nrepl-msg}]
  (let [session-id (-> session meta :id str)
        state @state-ref]
    (or (when-let [old-session (get-in state [:sessions session-id])]
          (swap! state-ref assoc-in [:sessions session-id :nrepl-msg] nrepl-msg)
          (assoc old-session :nrepl-msg nrepl-msg))
        (let [new-session
              {:session-id session-id
               :nrepl-msg nrepl-msg
               :build-id build-id
               :ns (or (some-> worker :state-ref deref :build-config :devtools :repl-init-ns)
                       'cljs.user)}]
          (swap! state-ref assoc-in [:sessions session-id] new-session)
          new-session))))

(defn ensure-symbol [ns]
  (cond
    (nil? ns)
    nil

    (string? ns)
    (symbol ns)

    (symbol? ns)
    ns

    :else
    nil))

(deftype UnknownTaggedLiteral [tag data])

(defmethod print-method UnknownTaggedLiteral
  [^UnknownTaggedLiteral this ^java.io.Writer w]
  (.write w (str "#" (.tag this) " " (.data this))))

(defn handle-print-return
  [{:keys [state-ref] :as svc}
   {:keys [op] :as msg}
   session-id
   nrepl-msg]
  ;; (tap> [:handle-print-return msg nrepl-msg svc])
  (case op
    :obj-result
    (let [{:keys [result]} msg]
      (do
        ;; this breaks printing in cursive ... no way to win ...
        #_(try
              (nrepl-out nrepl-msg
                ;; try to conform to emacs pprint support
                {:value (edn/read-string
                          {:default ->UnknownTaggedLiteral}
                          result)
                 :nrepl.middleware.print/keys #{:value}
                 :ns (str (get-in @state-ref [:sessions session-id :ns]))})
              (catch Exception e))

        (nrepl-out nrepl-msg
          {:value result
           :printed-value 1
           :ns (str (get-in @state-ref [:sessions session-id :ns]))})
        (nrepl-out nrepl-msg {:status :done})
        ;; just in case these weren't previously detected
        (when (or (= result ":cljs/quit")
                  (= result ":repl/quit"))
          ((::reset-session nrepl-msg)))))

    :client-not-found
    (do (nrepl-out nrepl-msg {:err (str "CLJS print failed, the runtime is gone!")})
        (nrepl-out nrepl-msg {:status :done}))

    :else
    (log/warn ::unexpected-eval-return {:msg msg})))

(defn handle-eval-return
  [{:keys [state-ref to-relay] :as svc}
   {:keys [op] :as msg}
   session-id
   nrepl-msg]
  ;; (tap> [:handle-eval-return msg nrepl-msg svc])
  (case op
    :eval-result-ref
    (let [{:keys [from eval-ns ref-oid]} msg
          call-id (new-call-id)
          print-msg
          {:op :obj-request
           :to from
           :call-id call-id
           :request-op :edn
           :oid ref-oid}]

      (swap! state-ref assoc-in [:sessions session-id :ns] eval-ns)
      (swap! state-ref assoc-in [:calls call-id] #(handle-print-return %1 %2 session-id nrepl-msg))
      (>!! to-relay print-msg))

    :eval-runtime-error
    (let [{:keys [from ex-oid]} msg
          call-id (new-call-id)
          print-msg
          {:op :obj-request
           :to from
           :call-id call-id
           :request-op :edn
           :oid ex-oid}]
      (swap! state-ref assoc-in [:calls call-id] #(handle-print-return %1 %2 session-id nrepl-msg))
      (>!! to-relay print-msg))

    :eval-compile-warnings
    (let [{:keys [warnings]} msg]
      (binding [warnings/*color* false]
        (doseq [warning warnings]
          (nrepl-out nrepl-msg {:err (with-out-str (warnings/print-short-warning warning))})))
      (nrepl-out nrepl-msg {:status :done}))

    :client-not-found
    (do (nrepl-out nrepl-msg {:err (str "CLJS eval failed, the runtime is gone!")})
        (nrepl-out nrepl-msg {:status :done}))

    :else
    (log/warn ::unexpected-eval-return {:msg msg})))

(defn handle-load-return
  [{:keys [state-ref] :as svc}
   {:keys [op] :as msg}
   session-id
   nrepl-msg]
  ;; (tap> [:handle-load-return msg nrepl-msg svc])
  (case op
    :eval-result-ref
    (do (nrepl-out nrepl-msg {:value "nil" :printed-value 1 :ns (str (get-in @state-ref [:sessions session-id :ns]))})
        (nrepl-out nrepl-msg {:status :done}))

    ;; FIXME: handle :eval-compile-error, :eval-runtime-error, :eval-compile-warnings

    :client-not-found
    (do (nrepl-out nrepl-msg {:err (str "CLJS load failed, the runtime is gone!")})
        (nrepl-out nrepl-msg {:status :done}))

    :else
    (log/warn ::unexpected-load-return {:msg msg})))

(defn do-nrepl
  [{:keys [supervisor to-relay state-ref] :as svc}
   {::keys [build-id repl-state] :keys [op code ns] :as nrepl-msg}]

  (let [worker (supervisor/get-worker supervisor build-id)]

    (cond
      ;; early detect quit requests so we can quit without requiring a runtime
      (and (= "eval" op)
           ;; don't care if there is anything (whitespace, commands) after a quit req
           ;; just throw it away
           (or (str/starts-with? code ":cljs/quit")
               (str/starts-with? code ":repl/quit")))
      (do (nrepl-out nrepl-msg {:value code :printed-value 1 :ns (str (:clj-ns repl-state))})
          (nrepl-out nrepl-msg {:status :done})
          ;; provided by nrepl middleware
          ((::reset-session nrepl-msg)))

      (not worker)
      (do (nrepl-out nrepl-msg {:err (str "The watch for build " build-id " is not running!")})
          (nrepl-out nrepl-msg {:status :done})
          ((::reset-session nrepl-msg)))

      ;; just use :default-runtime-id from worker
      ;; which is either the first or the last connected runtime, based on user prefs
      ;; in theory nrepl messages could supply a runtime-id but no client does that yet
      :else
      (let [{:keys [default-runtime-id]} (-> worker :state-ref deref)
            {:keys [session-id] :as session} (bridge-session svc worker nrepl-msg)]
        (cond
          (not default-runtime-id)
          (do (nrepl-out nrepl-msg {:err (str "No runtime for build " build-id " is connected!")})
              (nrepl-out nrepl-msg {:status :done})
              ((::reset-session nrepl-msg)))

          ;; good to go
          (= "eval" op)
          (let [call-id (new-call-id)
                eval-msg
                {:op :cljs-eval
                 :to default-runtime-id
                 :call-id call-id
                 :input {:code code
                         :ns (or (ensure-symbol ns)
                                 (:ns session))
                         :repl true}}]

            (swap! state-ref assoc-in [:calls call-id] #(handle-eval-return %1 %2 session-id nrepl-msg))
            (>!! to-relay eval-msg))

          ;; for now just use regular eval, piggieback does as well
          (= "load-file" op)
          (let [call-id (new-call-id)
                eval-msg
                {:op :cljs-eval
                 :to default-runtime-id
                 :call-id call-id
                 :input {:code (format "(cljs.core/load-file %s)" (pr-str (:file-path nrepl-msg)))
                         :ns (or (ensure-symbol ns)
                                 (:ns session))}}]

            (swap! state-ref assoc-in [:calls call-id] #(handle-load-return %1 %2 session-id nrepl-msg))
            (>!! to-relay eval-msg))

          ;; can't log entire message, too much garbage in it
          :else
          (log/warn ::unknown-nrepl-op {:op (:op nrepl-msg)}))))))

(defmulti do-relay
  (fn [svc {:keys [op] :as msg}] op)
  :default ::default)

(defmethod do-relay ::default
  [{:keys [state-ref] :as svc} {:keys [call-id] :as msg}]
  (if-not call-id
    (log/warn ::unhandled-relay-op msg)
    (let [handler (get-in @state-ref [:calls call-id])]
      (if-not handler
        (log/warn ::unhandled-relay-call-op msg)
        (handler svc msg)))))

(defn do-loop [{:keys [from-relay from-nrepl stop] :as svc}]
  (loop []
    (async/alt!!
      stop
      ([_] ::stopped)

      from-nrepl
      ([msg]
       ;; (tap> [::do-nrepl msg svc])
       (when (some? msg)
         (try
           (do-nrepl svc msg)
           (catch Exception e
             (log/warn-ex e ::do-nrepl-ex {:msg msg})))
         (recur)))

      from-relay
      ([msg]
       ;; (tap> [::do-relay msg svc])
       (when (some? msg)
         (try
           (do-relay svc msg)
           (catch Exception e
             (log/warn-ex e ::do-relay-ex msg)))
         (recur))))))

(defn nrepl-in [{:keys [from-nrepl] :as svc} msg]
  (>!! from-nrepl msg))

(defn start [relay supervisor]
  (let [to-relay
        (async/chan 10)

        from-relay
        (async/chan 256)

        connection-stop
        (relay/connect relay to-relay from-relay {})

        {:keys [op client-id] :as welcome-msg}
        (<!! from-relay)

        _
        (>!! to-relay {:op :hello
                       :client-info {:type :nrepl-bridge}})

        stop
        (async/chan)

        from-nrepl
        (async/chan 512)

        svc
        {:relay relay
         :supervisor supervisor
         :connection-stop connection-stop
         :stop stop
         :state-ref (atom {:calls {}})
         :relay-id client-id
         :to-relay to-relay
         :from-relay from-relay
         :from-nrepl from-nrepl}]

    (assoc svc
      :thread
      (async/thread
        (do-loop svc)
        (async/close! from-nrepl)
        (async/close! from-relay)
        (async/close! to-relay)))))

(defn stop [{:keys [stop thread] :as svc}]
  (async/close! stop)
  (<!! thread))
