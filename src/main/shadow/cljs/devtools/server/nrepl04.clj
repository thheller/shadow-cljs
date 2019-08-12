(ns shadow.cljs.devtools.server.nrepl04
  "nrepl 0.4+"
  (:refer-clojure :exclude (send select))
  (:require
    [clojure.pprint :refer (pprint)]
    [clojure.core.async :as async :refer (go <! >!)]
    [nrepl.middleware :as middleware]
    [nrepl.transport :as transport]
    [nrepl.server :as server]
    [nrepl.config :as nrepl-config]
    [shadow.jvm-log :as log]
    [shadow.build.api :refer (deep-merge)]
    [shadow.cljs.repl :as repl]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.devtools.server.fake-piggieback04 :as fake-piggieback]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.cljs.devtools.server.repl-impl :as repl-impl]
    [shadow.cljs.devtools.errors :as errors]
    [shadow.cljs.devtools.config :as config]
    [clojure.java.io :as io]
    [shadow.build.warnings :as warnings])
  (:import (java.io StringReader)))

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
  (let [quit-var #'api/*nrepl-quit-signal*
        quit-chan (get @session quit-var)]

    (when quit-chan
      (async/close! quit-chan))

    (swap! session assoc
      quit-var (async/chan)
      #'*ns* (get @session #'api/*nrepl-clj-ns*)
      #'api/*nrepl-cljs* nil
      #'cider.piggieback/*cljs-compiler-env* nil
      #'cemerick.piggieback/*cljs-compiler-env* nil)))

(defn handle-repl-result [worker {:keys [session] :as msg} actions]
  (log/debug ::eval-result {:result actions})
  (let [build-state (repl-impl/worker-build-state worker)
        repl-ns (-> build-state :repl-state :current-ns)]

    (doseq [{:keys [warnings result] :as action} actions]
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
        (send msg {:err (pr-str [:FIXME result])})))))

(defn do-cljs-eval [{::keys [build-id worker] :keys [ns session code runtime-id] :as msg}]
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

(defn worker-exit [session msg]
  (do (do-repl-quit session)
      (send msg {:err "\nThe REPL worker has stopped.\n"})
      (send msg {:value ":cljs/quit"
                 :printed-value 1
                 :ns (-> *ns* ns-name str)})))

(defn cljs-select [next]
  (fn [{:keys [session op transport] :as msg}]

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
            (next)
            )))))

(middleware/set-descriptor!
  #'cljs-select
  {:requires
   #{#'nrepl.middleware.session/session}

   :handles
   {"cljs/select" {}}})

(defn cljs-eval [next]
  (fn [{::keys [worker] :keys [op] :as msg}]
    (cond
      (and worker (= op "eval"))
      (do-cljs-eval msg)

      :else
      (next msg))))

(middleware/set-descriptor!
  #'cljs-eval
  {:requires
   #{#'cljs-select}

   :expects
   #{"eval"}})

(defn do-cljs-load-file [{::keys [worker] :keys [file file-path] :as msg}]
  (when-some [result (worker/load-file worker {:file-path file-path :source file})]
    (handle-repl-result worker msg result))
  (send msg {:status :done}))

(defn cljs-load-file [next]
  (fn [{::keys [worker] :keys [op] :as msg}]
    (cond
      (and worker (= op "load-file"))
      (do-cljs-load-file msg)

      :else
      (next msg))))

(middleware/set-descriptor!
  #'cljs-load-file
  {:requires
   #{#'cljs-select}

   :expects
   #{"load-file"}})

;; fake piggieback descriptor
(middleware/set-descriptor!
  #'cemerick.piggieback/wrap-cljs-repl
  {:requires
   #{#'cljs-select}

   ;; it doesn't do anything, it is just here for cider-nrepl
   :expects
   #{"eval" "load-file"}})

(middleware/set-descriptor!
  #'cider.piggieback/wrap-cljs-repl
  {:requires
   #{#'cljs-select}

   ;; it doesn't do anything, it is just here for cider-nrepl
   :expects
   #{"eval" "load-file"}})

(defn shadow-init [next]
  ;; can only assoc vars into nrepl-session
  (with-local-vars [init-complete false]

    (fn [{:keys [session] :as msg}]
      (when-not (get @session init-complete)

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
              (log/warn-ex e ::init-ns-ex {:init-ns init-ns}))
            (finally
              (swap! session assoc init-complete true)
              ))))

      (next msg))))

(middleware/set-descriptor!
  #'shadow-init
  {:requires
   #{#'nrepl.middleware.session/session}

   :expects
   #{"eval"}})

(defn load-middleware-sym [sym]
  {:pre [(qualified-symbol? sym)]}
  (let [sym-ns (-> sym (namespace) (symbol))]
    (require sym-ns)
    (or (find-var sym)
        (println (format "nrepl middleware not found: %s" sym)))
    ))

(defn load-middleware
  "loads vars for a sequence of symbols, expands if var refers to a vector of symbols"
  [input output]
  (loop [[sym & more :as rem] input
         output output]
    (cond
      (not (seq rem))
      output

      (not (qualified-symbol? sym))
      (do (log/warn ::invalid-middleware {:sym sym})
          (recur more output))

      :else
      (recur more
        (try
          (let [middleware-var (load-middleware-sym sym)
                middleware @middleware-var]
            (if (sequential? middleware)
              (load-middleware middleware output)
              (conj output middleware-var)))
          (catch Exception e
            (log/warn-ex e ::middleware-fail {:sym sym})
            output))))))

(defn make-middleware-stack [{:keys [cider middleware] :as config}]
  (-> []
      (into middleware)

      (cond->
        (and (io/resource "cider/nrepl.clj")
             (not (false? cider)))
        (conj 'cider.nrepl/cider-middleware))

      (into ['nrepl.middleware/wrap-describe
             'nrepl.middleware.interruptible-eval/interruptible-eval
             'nrepl.middleware.load-file/wrap-load-file

             ;; provided by fake-piggieback, only because tools expect piggieback
             'cemerick.piggieback/wrap-cljs-repl
             'cider.piggieback/wrap-cljs-repl

             ;; cljs support
             `cljs-load-file
             `cljs-eval
             `cljs-select
             `shadow-init

             'nrepl.middleware.session/add-stdin
             'nrepl.middleware.session/session])
      (load-middleware [])
      (middleware/linearize-middleware-stack)))

(defn start [config]
  (let [merged-config
        (deep-merge nrepl-config/config config)

        middleware-stack
        (make-middleware-stack merged-config)

        handler-fn
        ((apply comp (reverse middleware-stack)) server/unknown-op)

        {:keys [host port]
         :or {host "0.0.0.0"
              port 0}}
        merged-config]

    (log/debug ::config merged-config)

    (server/start-server
      :bind host
      :port port
      ;; breaks vim-fireplace, doesn't even show up in Cursive
      #_:greeting-fn
      #_(fn [transport]
          (transport/send transport {:out "Welcome to the shadow-cljs REPL!"}))
      :handler
      (fn [msg]
        (log/debug ::receive (dissoc msg :transport :session))
        (handler-fn msg)))))

(comment
  (prn (server/default-handler)))

(defn stop [server]
  (server/stop-server server))