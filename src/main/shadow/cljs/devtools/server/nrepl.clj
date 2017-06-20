(ns shadow.cljs.devtools.server.nrepl
  (:refer-clojure :exclude (send select))
  (:require [clojure.pprint :refer (pprint)]
            [clojure.tools.nrepl.middleware :as middleware]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.misc :as misc]
            [clojure.tools.nrepl.server :as server]
            [clojure.edn :as edn]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.api :as api]
            [clojure.tools.logging :as log]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.devtools.server.repl-impl :as repl-impl])
  (:import (java.io StringReader)))

(defn do-eval [msg args]
  [:foo])

(defn do-list-builds [msg args]
  [{:builds (config/load-cljs-edn)}])

(defn do-start-worker
  [msg args]
  [(api/start-worker (:build args) args)])

(defn rpc [{:keys [op transport args] :as msg} action]
  (try
    (let [replies (action msg (when args (edn/read-string args)))]
      (doseq [reply replies]
        (transport/send transport
          (misc/response-for msg {:value (pr-str reply)}))))
    (catch Exception e
      (prn [:error-while-processing op e])
      (transport/send transport
        (misc/response-for msg {:status :error :error (.getMessage e)}))))
  (transport/send transport
    (misc/response-for msg {:status :done})))

(def ^:dynamic *cljs-repl* nil)

(defn select [id]
  (if-not (api/get-worker id)
    [:no-worker id]
    (do (set! *cljs-repl* id)
        [:selected id])))

(defn send [req res]
  (transport/send (:transport req)
    (misc/response-for req res)))

(defn do-cljs-eval [build-id {:keys [transport code] :as msg}]
  (let [worker
        (api/get-worker build-id)

        reader
        (StringReader. code)]

    (loop []
      (when-let [repl-state (repl-impl/worker-repl-state worker)]

        ;; need the repl state to properly support reading ::alias/foo
        (let [{:keys [eof? form] :as read-result}
              (repl/read-one repl-state reader {})]

          (cond
            eof?
            :eof

            (nil? form)
            (recur)

            ;; FIXME: should these unselect?
            (= :repl/quit form)
            :quit

            (= :cljs/quit form)
            :quit

            :else
            (when-some [result (worker/repl-eval worker ::stdin read-result)]
              (prn [:result result])
              (send msg {:value result})
              (recur)
              )))))

    (send msg {:status :done})
    ))

(defn wrap-handler
  [next]
  (fn [{:keys [op session] :as msg}]
    (let [repl-var #'*cljs-repl*]

      (when-not (contains? @session repl-var)
        (swap! session assoc repl-var nil))

      (let [cljs-repl (get @session repl-var)]
        (cond
          (and (= "eval" op) (some? cljs-repl))
          (do-cljs-eval cljs-repl msg)

          :else ;; treat as CLJ
          (next msg)
          )))))

(middleware/set-descriptor!
  #'wrap-handler
  {:requires
   #{"clone"}

   :expects
   #{"eval" "load-file"}

   :handles
   {"cljs/eval" {}}})

(defn start
  [{:keys [host port]
    :or {host "localhost"
         port 0}
    :as config}]

  (let [middlewares
        [#'clojure.tools.nrepl.middleware/wrap-describe
         #'clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval
         #'clojure.tools.nrepl.middleware.load-file/wrap-load-file
         #'wrap-handler
         #'clojure.tools.nrepl.middleware.session/add-stdin
         #'clojure.tools.nrepl.middleware.session/session]

        stack
        (middleware/linearize-middleware-stack middlewares)

        handler
        ((apply comp (reverse stack)) server/unknown-op)]

    (server/start-server :bind host :port port :handler handler)))

(defn stop [server]
  (server/stop-server server))