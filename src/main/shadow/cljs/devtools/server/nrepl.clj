(ns shadow.cljs.devtools.server.nrepl
  "nrepl 0.4+"
  (:refer-clojure :exclude (send select))
  (:require
    [clojure.java.io :as io]
    [cider.piggieback :as piggieback]
    [nrepl.middleware :as middleware]
    [nrepl.server :as server]
    [nrepl.config :as nrepl-config]
    [shadow.jvm-log :as log]
    [shadow.build.api :refer (deep-merge)]
    [shadow.cljs.devtools.server.nrepl-impl :as nrepl-impl]
    [shadow.cljs.devtools.api :as api]))

(defn shadow-init [next]
  ;; can only assoc vars into nrepl-session
  (with-local-vars [init-complete false]

    (fn [{:keys [session] :as msg}]
      (when-not (get @session init-complete)
        (try
          (nrepl-impl/shadow-init-ns! msg)
          (finally
            (swap! session assoc init-complete true)
            )))

      (next msg))))

(defn shadow-cljs-repl [repl-env & options]
  {:pre [(keyword? repl-env)]}
  (api/nrepl-select repl-env))

;; api method, does too much stuff we don't need
;; shouldn't be used at all but tools like vim-fireplace have (cider.piggieback/cljs-repl ...) hardcoded
(alter-var-root #'piggieback/cljs-repl (constantly shadow-cljs-repl))

(defn middleware [next]
  (fn [msg]
    (nrepl-impl/handle msg next)))

(middleware/set-descriptor! #'shadow-init
  {:requires #{#'middleware}
   :expects #{"eval" "load-file"}})

;; our middleware must run before piggieback
;; we intercept the work it would do, so we don't have to emulate it
(middleware/set-descriptor! #'middleware
  {:requires #{"clone"}
   :expects #{"eval" "load-file" #'piggieback/wrap-cljs-repl}})

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

(defn make-middleware-stack [{:keys [cider] :as config}]
  (-> []
      (into (:middleware config))

      (cond->
        (and (io/resource "cider/nrepl.clj")
             (not (false? cider)))
        (conj 'cider.nrepl/cider-middleware))

      (into ['nrepl.middleware/wrap-describe
             'nrepl.middleware.interruptible-eval/interruptible-eval
             'nrepl.middleware.load-file/wrap-load-file

             ;; cljs support
             `shadow-init ;; for :init-ns support
             `middleware

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

        {:keys [host port transport-fn]
         :or {host "0.0.0.0"
              port 0}}
        merged-config

        transport-fn
        (requiring-resolve (or transport-fn 'nrepl.transport/bencode))]

    (log/debug ::config merged-config)

    (server/start-server
      :bind host
      :port port
      :transport-fn transport-fn
      ;; breaks vim-fireplace, doesn't even show up in Cursive
      #_:greeting-fn
      #_(fn [transport]
          (transport/send transport {:out "Welcome to the shadow-cljs REPL!"}))
      :handler
      (fn [msg]
        ;; (log/debug ::receive (dissoc msg :transport :session))
        (handler-fn msg)))))

(comment
  (prn (server/default-handler)))

(defn stop [server]
  (server/stop-server server))