(ns shadow.cljs.devtools.server.nrepl
  "tools.nrepl 0.2"
  (:refer-clojure :exclude (send select))
  (:require
    [clojure.pprint :refer (pprint)]
    [clojure.core.async :as async :refer (go <! >!)]
    [clojure.tools.nrepl.middleware :as middleware]
    [clojure.tools.nrepl.transport :as transport]
    [clojure.tools.nrepl.server :as server]
    [clojure.tools.nrepl.middleware.session :as session]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.server.fake-piggieback :as fake-piggieback]
    [shadow.cljs.devtools.server.nrepl-impl :as nrepl-impl]
    [shadow.cljs.devtools.config :as config]))

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
  (fn [{:keys [session] :as msg}]
    (-> msg
        (assoc ::nrepl-impl/send send)
        (nrepl-impl/handle next))))

(middleware/set-descriptor!
  #'middleware
  {:requires
   #{#'clojure.tools.nrepl.middleware.session/session}

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
   #{#'clojure.tools.nrepl.middleware.session/session}

   :expects
   #{"eval"}})

(defn middleware-load [sym]
  {:pre [(qualified-symbol? sym)]}
  (let [sym-ns (-> sym (namespace) (symbol))]
    (require sym-ns)
    (or (find-var sym)
        (println (format "nrepl middleware not found: %s" sym)))
    ))

;; automatically add cider when on the classpath
(defn get-cider-middleware []
  (try
    (require 'cider.nrepl)
    (->> @(find-var 'cider.nrepl/cider-middleware)
         (map find-var)
         (into []))
    (catch Exception e
      [])))

(defn make-middleware-stack [extra-middleware]
  (-> []
      (into (->> extra-middleware
                 (map middleware-load)
                 (remove nil?)))
      (into (get-cider-middleware))
      (into [#'clojure.tools.nrepl.middleware/wrap-describe
             #'clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval
             #'clojure.tools.nrepl.middleware.load-file/wrap-load-file

             ;; cljs support
             #'middleware
             #'shadow-init

             #'clojure.tools.nrepl.middleware.session/add-stdin
             #'clojure.tools.nrepl.middleware.session/session])
      (middleware/linearize-middleware-stack)))

(defn start
  [{:keys [host port middleware]
    :or {host "0.0.0.0"
         port 0}
    :as config}]

  (let [middleware-stack
        (make-middleware-stack middleware)

        handler-fn
        ((apply comp (reverse middleware-stack)) server/unknown-op)]

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