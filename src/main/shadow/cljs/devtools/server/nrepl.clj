(ns shadow.cljs.devtools.server.nrepl
  (:refer-clojure :exclude (send select))
  (:require [clojure.pprint :refer (pprint)]
            [clojure.tools.nrepl.middleware :as middleware]
            [clojure.tools.nrepl.middleware.session :as nrepl-session]
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

(def ^:dynamic *cljs-repl* nil)

(defn select [id]
  (if-not (api/get-worker id)
    [:no-worker id]
    (do (set! *cljs-repl* id)
        [:selected id])))

(defn send [req res]
  (transport/send (:transport req)
    (misc/response-for req res)))

(defn cljs-eval
  [next]
  (fn [{:keys [session code] :as msg}]
    (let [build-id
          (get @session #'*cljs-repl*)

          worker
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

              (= :repl/quit form)
              (do (swap! session dissoc #'*cljs-repl*)
                  (send msg {:value :repl/quit}))

              (= :cljs/quit form)
              (do (swap! session dissoc #'*cljs-repl*)
                  (send msg {:value :cljs/quit}))

              :else
              (when-some [result (worker/repl-eval worker ::stdin read-result)]
                (prn [:result result])

                (case (:type result)
                  :repl/result
                  (send msg result) ;; :value is already a edn string

                  :repl/set-ns-complete
                  (send msg {:status #{:repl/set-ns-complete} :value :repl/set-ns-complete})

                  :repl/require-complete
                  nil

                  :repl/interrupt
                  nil

                  :repl/timeout
                  (send msg {:status #{:repl/timeout}})

                  :repl/no-eval-target
                  (send msg {:status #{:repl/no-eval-target} :value :repl/no-eval-target})

                  :repl/too-many-eval-clients
                  (send msg {:status #{:repl/too-many-clients}})

                  :else
                  (send msg {:value (pr-str result)}))

                (recur)
                )))))

      (send msg {:status :done})
      )

    ))

(middleware/set-descriptor!
  #'cljs-eval
  {:requires
   #{"clone"}

   :expects
   #{}

   :handles
   {"eval" {}}})

(defn start
  [{:keys [host port]
    :or {host "localhost"
         port 0}
    :as config}]

  (let [clj-stack
        (-> [#'clojure.tools.nrepl.middleware/wrap-describe
             #'clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval
             #'clojure.tools.nrepl.middleware.load-file/wrap-load-file]
            (middleware/linearize-middleware-stack))

        clj-handler
        ((apply comp (reverse clj-stack)) server/unknown-op)

        cljs-stack
        (-> [#'cljs-eval]
            (middleware/linearize-middleware-stack))

        cljs-handler
        ((apply comp (reverse cljs-stack)) server/unknown-op)

        select-handler
        (fn [{:keys [op session] :as msg}]
          (let [repl-var #'*cljs-repl*]
            (when-not (contains? @session repl-var)
              (swap! session assoc repl-var nil))

            (let [select-id (get @session repl-var)]
              (if select-id
                (do (prn [:nrepl-op op (keys msg)])
                    (cljs-handler msg))
                (clj-handler msg)))))

        default-stack
        (-> [#'clojure.tools.nrepl.middleware.session/add-stdin
             #'clojure.tools.nrepl.middleware.session/session]
            (middleware/linearize-middleware-stack))

        root-handler
        ((apply comp (reverse default-stack)) select-handler)]

    (server/start-server
      :bind host
      :port port
      :handler root-handler)))

(comment
  (prn (server/default-handler)))

(defn stop [server]
  (server/stop-server server))