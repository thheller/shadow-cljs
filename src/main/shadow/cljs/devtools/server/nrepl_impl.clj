(ns shadow.cljs.devtools.server.nrepl-impl
  (:refer-clojure :exclude (send))
  (:require
    [cider.piggieback :as piggieback]
    [clojure.core.async :as async :refer (>!! <!! alt!! thread)]
    [nrepl.transport :as transport]
    [shadow.jvm-log :as log]
    [shadow.debug :as dbg :refer (?> ?-> ?->>)]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server.repl-impl :as repl-impl]
    [clojure.edn :as edn]
    [shadow.remote.relay.api :as relay]
    [nrepl.core :as nrepl])
  (:import [java.io StringReader]))

(def ^:dynamic *repl-state* nil)

;; this runs in an eval context and therefore cannot modify session directly
;; must use set! as they are captured AFTER eval finished and would overwrite
;; what we did in here. reading is fine though.
(defn repl-init
  [msg
   build-id
   {:keys [ns]
    :as opts}]

  (let [worker (api/get-worker build-id)]
    (when-not worker
      (throw (ex-info "watch for build not running" {:build-id build-id})))

    (let [init-ns
          (or ns
              (some-> worker :state-ref deref :build-config :devtools :repl-init-ns)
              'cljs.user)]
      ;; must keep the least amount of state here since it will be shared by clones
      (set! *repl-state*
        {:build-id build-id
         :opts opts
         :cljs-ns init-ns
         :clj-ns *ns*})

      ;; doing this to make cider prompt not show "user" as prompt after calling this
      (set! *ns* (create-ns init-ns))))

  ;; make tools happy, we do not use it
  ;; its private for some reason so we can't set! it directly
  (let [pvar #'piggieback/*cljs-compiler-env*]
    (when (thread-bound? pvar)
      (.set pvar
        (reify
          clojure.lang.IDeref
          (deref [_]
            (some->
              (api/get-worker build-id)
              :state-ref
              deref
              :build-state
              :compiler-env)))))))

(defn reset-session [session]
  (let [{:keys [clj-ns] :as repl-state} (get @session #'*repl-state*)]
    ;; (tap> [:reset-session repl-state session])
    (swap! session assoc
      #'*ns* clj-ns
      #'*repl-state* nil
      #'cider.piggieback/*cljs-compiler-env* nil)

    clj-ns))

(defn set-build-id [{:keys [op session] :as msg}]
  ;; re-create this for every eval so we know exactly which msg started a REPL
  ;; only eval messages can "upgrade" a REPL
  (when (= "eval" op)
    (swap! session assoc #'api/*nrepl-init* #(repl-init msg %1 %2)))

  ;; DO NOT PUT ATOM'S INTO THE SESSION!
  ;; clone will result in two session using the same atom
  ;; so any change to the atom will affect all session clones
  (when-not (contains? @session #'*repl-state*)
    (swap! session assoc #'*repl-state* nil))

  (let [{:keys [build-id] :as repl-state} (get @session #'*repl-state*)]
    (if-not build-id
      msg
      (assoc msg
        ::repl-state repl-state
        ;; keeping these since cider uses it in some middleware
        ;; not keeping worker reference in repl-state since that would leak
        ;; since we can't cleanup sessions reliably
        ::worker (api/get-worker build-id)
        ::build-id build-id))))

(defn shadow-init-ns!
  [{:keys [session] :as msg}]
  (let [config
        (config/load-cljs-edn)

        init-ns
        (or (get-in config [:nrepl :init-ns])
            (get-in config [:repl :init-ns])
            'shadow.user)]

    (try
      (require init-ns)
      (swap! session assoc #'*ns* (find-ns init-ns))
      (catch Exception e
        (log/warn-ex e ::init-ns-ex {:init-ns init-ns})))))

(defn nrepl-merge [{:keys [transport session id] :as req} {:keys [status] :as msg}]
  (-> msg
      (cond->
        id
        (assoc :id id)
        session
        (assoc :session (-> session meta :id))
        (and (some? status)
             (not (coll? status)))
        (assoc :status #{status}))))

(defn nrepl-out [{:keys [transport session id] :as req} {:keys [status] :as msg}]
  (let [res (nrepl-merge req msg)]

    ;; (?> res :nrepl-out)

    (try
      (transport/send transport res)
      (catch Exception ex
        ;; just unconditionally reset the session back to CLJ
        ;; sends most likely fail because of closed sockets
        ;; so this really doesn't matter, just want to avoid getting here too many times
        (reset-session session)
        (log/debug-ex ex ::nrepl-out-failed msg)))))

(defn do-cljs-eval
  [{::keys [worker repl-state] :keys [code session ns] :as msg}]
  ;; just give up on trying to maintain session over nrepl
  ;; piggieback doesn't and its just too painful given that there
  ;; are absolutely no lifecycle hooks or signals if a session
  ;; actually still cares about receiving results or stdout/stderr
  ;; instead just go off once and clean up after

  (if-not worker
    (let [clj-ns (reset-session (:session msg))]
      (nrepl-out msg {:err "The worker for this REPL has exited. You are now in CLJ again.\n"})
      (nrepl-out msg {:value "nil"
                      :printed-value 1
                      :ns (str clj-ns)})
      (nrepl-out msg {:status :done}))

    (let [{:keys [relay]}
          (api/get-runtime!)

          result
          (repl-impl/do-repl
            worker
            relay
            (StringReader. code)
            (async/chan)
            {:init-state
             {:ns (or (and (seq ns) (symbol ns))
                      (:cljs-ns repl-state))
              :runtime-id (get-in repl-state [:opts :runtime-id])}

             :repl-prompt
             (fn repl-prompt [repl-state])

             :repl-read-ex
             (fn repl-read-ex [repl-state ex]
               (nrepl-out msg {:err (.getMessage ex)}))

             :repl-result
             (fn repl-result
               [{:keys [ns] :as repl-state} result-as-printed-string]

               ;; need to remember for later evals
               (swap! session assoc-in [#'*repl-state* :cljs-ns] ns)

               (if-not result-as-printed-string
                 ;; repl-result is called even in cases there is no result
                 ;; eg. compile errors, warnings
                 (nrepl-out msg {:value "nil"
                                 :printed-value 1
                                 :ns (str ns)})
                 ;; regular return value
                 (try
                   (nrepl-out msg
                     {:value (edn/read-string
                               {:default
                                ;; FIXME: piggieback uses own UnknownTaggedLiteral
                                ;; not sure why? seems like it might as well skip trying to use it
                                ;; and use the result we had instead.
                                (fn [sym val]
                                  (throw (ex-info "unknown edn tags" {:sym sym :val val})))}
                               result-as-printed-string)
                      :nrepl.middleware.print/keys #{:value}
                      :ns (str ns)})
                   (catch Exception e
                     (log/debug-ex e ::repl-read)
                     (nrepl-out msg
                       {:value result-as-printed-string
                        :printed-value 1
                        :ns (str ns)})))))

             :repl-stderr
             (fn repl-stderr [repl-state text]
               (nrepl-out msg {:err text}))

             :repl-stdout
             (fn repl-stdout [repl-state text]
               (nrepl-out msg {:out text}))})]

      (when (or (= :cljs/quit result)
                (= :repl/quit result))
        (let [clj-ns (reset-session (:session msg))]
          (nrepl-out msg {:err "Exited CLJS session. You are now in CLJ again.\n"})
          (nrepl-out msg {:value (str result)
                          :printed-value 1
                          :ns (str clj-ns)})))

      (nrepl-out msg {:status :done}))))

(defonce remote-clients-ref
  (atom {}))

(defn handle [{:keys [transport session op] :as msg} next]
  (case op
    "shadow-remote-init"
    (let [{:keys [relay] :as runtime} (api/get-runtime!)

          to-relay
          (async/chan 10)

          from-relay
          (async/chan 256)

          connection-stop
          (relay/connect relay to-relay from-relay {})

          session-id
          (-> msg :session meta :id)

          {:keys [client-id] :as welcome-msg}
          (<!! from-relay)

          [decoder encoder]
          (case (:data-type msg)
            "edn"
            [(:edn-reader runtime) pr-str]
            "transit"
            [(:transit-read runtime) (:transit-str runtime)]
            nil)]

      (if-not decoder
        (try
          (transport/send transport (nrepl-merge msg {:err "Invalid :data-type, edn|transit expected."}))
          (catch Exception ex
            ;; immediately stop session if we can't even send init error
            (async/close! connection-stop)
            (log/debug-ex ex ::remote-out-failed msg)))

        (thread
          (swap! remote-clients-ref assoc session-id
            {:to-relay to-relay
             :from-relay from-relay
             :connection-stop connection-stop
             :session-id session-id
             :client-id client-id
             :encoder encoder
             :decoder decoder})

          (>!! to-relay
               {:op :hello
                ;; FIXME: get more data out of msg maybe?
                :client-info {:type :nrepl-session}})

          ;; forward everything from the relay to nrepl endpoint
          ;; relay will be sending heartbeats and if they can't be delivered the connection will stop
          ;; remote-clients-ref is sort of global but we can get away with that for now
          (loop []
            (when-some [x (<!! from-relay)]
              (try
                (transport/send transport (nrepl-merge msg {:op "shadow-remote-msg" :data (encoder x)}))
                (catch Exception ex
                  (async/close! connection-stop)
                  (log/debug-ex ex ::remote-out-failed msg)))
              (recur)))

          (swap! remote-clients-ref dissoc session-id)
          )))

    "shadow-remote-msg"
    (let [session-id (-> msg :session meta :id)
          client (get @remote-clients-ref session-id)]

      (if-not client
        (try
          (transport/send transport (nrepl-merge msg {:err "session did not shadow-remote-init"}))
          (catch Exception ex
            (log/debug-ex ex ::remote-out-failed msg)))

        (let [{:keys [decoder to-relay]} client
              remote-msg (decoder (:data msg))]
          (async/offer! to-relay remote-msg)
          )))

    "shadow-remote-stop"
    (let [session-id (-> msg :session meta :id)
          client (get @remote-clients-ref session-id)]

      ;; nothing to do if there is no client session
      (when client
        (async/close! (:connection-stop client))))

    ;; default case
    (let [{::keys [build-id] :as msg} (set-build-id msg)]
      ;; (?> msg :msg)

      (cond
        (and build-id (= op "eval"))
        (do-cljs-eval msg)

        (and build-id (= op "load-file"))
        (do-cljs-eval (assoc msg :code (format "(cljs.core/load-file %s)" (pr-str (:file-path msg)))))

        :else
        (next msg)))))