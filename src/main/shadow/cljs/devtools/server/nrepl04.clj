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
    [shadow.cljs.devtools.server.fake-piggieback04 :as fake-piggieback]
    [shadow.cljs.devtools.server.nrepl-impl :as nrepl-impl]
    [clojure.java.io :as io]))

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

(defn middleware [next]
  (fn [msg]
    (-> msg
        (assoc ::nrepl-impl/send send)
        (nrepl-impl/handle next))))

(middleware/set-descriptor!
  #'middleware
  {:requires
   #{#'nrepl.middleware.session/session}

   :expects
   #{"eval" "load-file"}})

(defn shadow-init [next]
  ;; can only assoc vars into nrepl-session
  (with-local-vars [init-complete false]

    (fn [{:keys [session] :as msg}]
      (when-not (get @session init-complete)
        (try
          (nrepl-impl/shadow-init! msg)
          (finally
            (swap! session assoc init-complete true)
            )))

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

             ;; cljs support
             `middleware
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
        ;; (log/debug ::receive (dissoc msg :transport :session))
        (handler-fn msg)))))

(comment
  (prn (server/default-handler)))

(defn stop [server]
  (server/stop-server server))