(ns shadow.cljs.devtools.server.nrepl-impl
  (:refer-clojure :exclude (send))
  (:require [shadow.cljs.devtools.api :as api]
            [clojure.core.async :as async]
            [shadow.jvm-log :as log]
            [shadow.cljs.devtools.server.repl-impl :as repl-impl]
            [shadow.build.warnings :as warnings]
            [shadow.cljs.devtools.errors :as errors]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.config :as config])
  (:import [java.io StringReader]))

(defn do-repl-quit [session]
  (let [quit-var #'api/*nrepl-quit-signal*
        quit-chan (get @session quit-var)]

    (when quit-chan
      (async/close! quit-chan))

    (swap! session assoc
      quit-var (async/chan)
      #'*ns* (get @session #'api/*nrepl-clj-ns*)
      #'api/*nrepl-cljs* nil
      #'cider.piggieback/*cljs-compiler-env* nil)))

(defn handle-repl-result [worker {::keys [send] :keys [session] :as msg} result]
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
      (do (do-repl-quit session)
          (send msg {:err "The REPL worker has stopped.\n"})
          (send msg {:value ":cljs/quit"
                     :printed-value 1
                     :ns (-> *ns* ns-name str)}))

      ;; :else
      (send msg {:err (pr-str [:FIXME result])}))

    ))

(defn do-cljs-eval [{::keys [worker send] :keys [ns session code runtime-id] :as msg}]
  (let [reader (StringReader. code)

        session-id
        (-> session meta :id str)]

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

(defn worker-exit [session {::keys [send] :as msg}]
  (do (do-repl-quit session)
      (send msg {:err "\nThe REPL worker has stopped.\n"})
      (send msg {:value ":cljs/quit"
                 :printed-value 1
                 :ns (-> *ns* ns-name str)})))

(defn set-worker [{:keys [session] :as msg}]
  (let [repl-var #'api/*nrepl-cljs*]
    (when-not (contains? @session repl-var)
      (swap! session assoc
        repl-var nil
        #'api/*nrepl-quit-signal* (async/chan)
        #'api/*nrepl-active* true
        #'api/*nrepl-clj-ns* nil))

    (swap! session assoc
      #'api/*nrepl-worker-exit* #(worker-exit session msg)
      #'api/*nrepl-session* session
      #'api/*nrepl-msg* msg)

    (let [build-id
          (get @session repl-var)

          worker
          (when build-id
            (api/get-worker build-id))]

      ;; (prn [:cljs-select op build-id (some? worker) (keys msg)])
      #_(when (= op "eval")
          (println)
          (println (:code msg))
          (println)
          (flush))

      (-> msg
          (cond->
            worker
            ;; FIXME: add :cljs.env/compiler key for easier access?
            (assoc ::worker worker ::build-id build-id))
          ))))

(defn do-cljs-load-file [{::keys [worker send] :keys [file file-path] :as msg}]
  (when-some [result (worker/load-file worker {:file-path file-path :source file})]
    (handle-repl-result worker msg result))
  (send msg {:status :done}))

(defn shadow-init!
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

(defn handle [{:keys [op] :as msg} next]
  (let [{::keys [worker] :as msg} (set-worker msg)]
    (log/debug ::handle {:msg-op op :worker (some? worker)})
    (cond
      (and worker (= op "eval"))
      (do-cljs-eval msg)

      (and worker (= op "load-file"))
      (do-cljs-load-file msg)

      :else
      (next msg))))