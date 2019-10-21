(ns shadow.cljs.devtools.server.nrepl-impl
  (:refer-clojure :exclude (send))
  (:require [shadow.cljs.devtools.api :as api]
            [clojure.core.async :as async :refer (go <! >! alt!)]
            [shadow.jvm-log :as log]
            [shadow.cljs.devtools.server.repl-impl :as repl-impl]
            [shadow.build.warnings :as warnings]
            [shadow.cljs.devtools.errors :as errors]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.config :as config]
            [nrepl.transport :as transport]
            [cider.piggieback :as piggieback])
  (:import [java.io StringReader]
           [clojure.lang Var]))

(def ^:dynamic *repl-state* nil)

(defn send [{:keys [transport session id] :as req} {:keys [status] :as msg}]
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
    (transport/send transport res)))

(defn do-repl-quit [session]
  (let [{:keys [clj-ns watch-chan] :as repl-state} (get @session #'*repl-state*)]

    ;; only close watch-chan if session originally started it
    ;; clones would otherwise shutdown all output
    (when (= session (:session repl-state))
      (async/close! watch-chan))

    (swap! session assoc
      #'*ns* clj-ns
      #'*repl-state* nil
      #'cider.piggieback/*cljs-compiler-env* nil)))

(defn worker-exit [session msg]
  (do-repl-quit session)

  ;; replying with msg id that started the REPL, not the last msg
  (send msg {:err "\nThe REPL worker has stopped.\n"})
  (send msg {:value ":cljs/quit"
             :printed-value 1
             :ns (-> *ns* ns-name str)}))

(defn handle-repl-result [worker {:keys [session] :as msg} result]
  (log/debug ::eval-result {:result result})

  (let [build-state (repl-impl/worker-build-state worker)
        repl-ns (-> build-state :repl-state :current-ns)]

    (case (:type result)
      :repl/results
      (let [{:keys [results]} result]
        (doseq [{:keys [warnings result] :as action} results]
          (binding [warnings/*color* false]
            (doseq [warning warnings]
              (send msg {:err (with-out-str (warnings/print-short-warning warning))})))

          (case (:type result)
            :repl/result
            (send msg {:value (:value result)
                       :printed-value 1
                       :ns (pr-str repl-ns)})

            :repl/set-ns-complete
            (send msg {:value (pr-str repl-ns)
                       :printed-value 1
                       :ns (pr-str repl-ns)})

            (:repl/invoke-error
              :repl/require-error)
            (send msg {:err (or (:stack result)
                                (:error result))})

            :repl/require-complete
            (send msg {:value "nil"
                       :printed-value 1
                       :ns (pr-str repl-ns)})

            :repl/error
            (send msg {:err (errors/error-format (:ex result))})

            ;; :else
            (send msg {:err (pr-str [:FIXME action])}))))

      :repl/interrupt
      nil

      :repl/timeout
      (send msg {:err "REPL command timed out.\n"})

      :repl/no-runtime-connected
      (send msg {:err "No application has connected to the REPL server. Make sure your JS environment has loaded your compiled ClojureScript code.\n"})

      :repl/too-many-runtimes
      (send msg {:err "There are too many JS runtimes, don't know which to eval in.\n"})

      :repl/error
      (send msg {:err (errors/error-format (:ex result))})

      :repl/worker-stop
      :already-handled ;; in go created in repl-init

      ;; :else
      (send msg {:err (pr-str [:FIXME result])}))))

(defn do-cljs-eval [{::keys [worker] :keys [ns session code runtime-id] :as msg}]
  (let [reader (StringReader. code)

        session-id
        (-> session meta :id str)

        {:keys [last-msg-ref] :as repl-state}
        (get @session #'*repl-state*)]

    ;; :last-msg-ref is used by the print loop started by repl-init
    ;; to ensure that all prints use the latest message id when sending it out
    ;; FIXME: this is completely pointless with clone'd sessions
    ;; lets just hope eval is only ever done against one of the clones ...
    (reset! last-msg-ref msg)

    (loop []
      (when-let [build-state (repl-impl/worker-build-state worker)]

        ;; need the repl state to properly support reading ::alias/foo
        (let [read-opts
              (-> {}
                  (cond->
                    (seq ns)
                    (assoc :ns (symbol ns))))

              {:keys [eof? error? ex form] :as read-result}
              (repl/read-one build-state reader read-opts)]

          (cond
            eof?
            :eof

            error?
            (do (send msg {:err (str "Failed to read input: " ex)})
                (recur))

            (nil? form)
            (recur)

            (= :repl/quit form)
            (do (do-repl-quit session)
                (send msg {:value ":repl/quit"
                           :printed-value 1
                           :ns (-> *ns* ns-name str)}))

            (= :cljs/quit form)
            (do (do-repl-quit session)
                (send msg {:value ":cljs/quit"
                           :printed-value 1
                           :ns (-> *ns* ns-name str)}))

            ;; Cursive supports
            ;; {:status :eval-error :ex <exception name/message> :root-ex <root exception name/message>}
            ;; {:err string} prints to stderr
            :else
            (when-some [result (worker/repl-eval worker session-id runtime-id read-result)]
              (handle-repl-result worker msg result)
              (recur))
            ))))

    (send msg {:status :done})
    ))


;; this runs in an eval context and therefore cannot modify session directly
;; must use set! as they are captured AFTER eval finished and would overwrite
;; what we did in here. reading is fine though.
(defn repl-init
  [{:keys [session] :as msg}
   {:keys [proc-stop build-id] :as worker}
   opts]

  (let [watch-chan
        (-> (async/sliding-buffer 100)
            (async/chan))

        last-msg-ref
        (atom msg)]

    (set! *repl-state*
      ;; FIXME: atom shared between all clones
      ;; probably causes out/err issues
      {:last-msg-ref last-msg-ref
       :session session
       :watch-chan watch-chan
       :worker worker
       :build-id build-id
       :opts opts
       :clj-ns *ns*})

    ;; doing this to make cider prompt not show "user" as prompt after calling this
    (let [repl-ns (some-> worker :state-ref deref :build-state :repl-state :current :ns)]
      (set! *ns* (create-ns (or repl-ns 'cljs.user))))

    ;; cleanup if worker exits
    (go (<! proc-stop)
        (async/close! watch-chan)
        (worker-exit session msg))

    ;; watch worker for specific messages (ie. out/err)
    ;; send :err/:out with latest msg id to make tools happy
    ;; technically this can lead to wrong ids in async code
    ;; but better than always using the first msg id maybe?
    (go (try
          (loop []
            (when-some [{:keys [type text] :as msg} (<! watch-chan)]
              (case type
                :repl/out
                (send @last-msg-ref {:out (str text "\n")})

                :repl/err
                (send @last-msg-ref {:err (str text "\n")})

                ;; not interested in any other message for now
                :ignored)
              (recur)))

          (catch Exception e
            (log/debug-ex e ::nrepl-print-loop-ex)
            (async/close! watch-chan))))

    (worker/watch worker watch-chan true)

    ;; make tools happy, we do not use it
    ;; its private for some reason so we can't set! it directly
    (let [pvar #'piggieback/*cljs-compiler-env*]
      (when (thread-bound? pvar)
        (.set pvar
          (reify
            clojure.lang.IDeref
            (deref [_]
              (some-> worker :state-ref deref :build-state :compiler-env))))))))

(defn set-worker [{:keys [op session] :as msg}]
  ;; re-create this for every eval so we know exactly which msg started a REPL
  ;; only eval messages can "upgrade" a REPL
  (when (= "eval" op)
    (swap! session assoc #'api/*nrepl-init* #(repl-init msg %1 %2)))

  ;; DO NOT PUT ATOM'S INTO THE SESSION!
  ;; clone will result in two session using the same atom
  ;; so any change to the atom will affect all session clones
  (when-not (contains? @session #'*repl-state*)
    (swap! session assoc #'*repl-state* nil))

  (let [{:keys [build-id worker] :as repl-state} (get @session #'*repl-state*)]
    (if-not build-id
      msg
      (assoc msg ::repl-state repl-state ::worker worker ::build-id build-id))))

(defn do-cljs-load-file [{::keys [worker] :keys [file file-path] :as msg}]
  (when-some [result (worker/load-file worker {:file-path file-path :source file})]
    (handle-repl-result worker msg result))
  (send msg {:status :done}))

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

(defn handle [{:keys [session op] :as msg} next]
  (let [{::keys [worker] :as msg} (set-worker msg)]
    (log/debug ::handle {:session-id (-> session meta :id)
                         :msg-op op
                         :worker (some? worker)
                         :code (when-some [code (:code msg)]
                                 (subs code 0 (min (count code) 100)))})
    (cond
      (and worker (= op "eval"))
      (do-cljs-eval msg)

      (and worker (= op "load-file"))
      (do-cljs-load-file msg)

      :else
      (next msg))))