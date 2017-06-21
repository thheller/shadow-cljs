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
            [shadow.cljs.devtools.server.repl-impl :as repl-impl]
            [shadow.cljs.devtools.fake-piggieback :as fake-piggieback])
  (:import (java.io StringReader)))

(defn send [req res]
  (transport/send (:transport req)
    (misc/response-for req res)))

(defn do-cljs-eval [{::keys [build-id worker] :keys [session code] :as msg}]
  (let [reader (StringReader. code)]

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
            (do (swap! session dissoc #'api/*nrepl-cljs*)
                (send msg {:value ":repl/quit"}))

            (= :cljs/quit form)
            (do (swap! session dissoc #'api/*nrepl-cljs*)
                (send msg {:value ":cljs/quit"}))

            ;; Cursive supports
            ;; {:status :eval-error :ex <exception name/message> :root-ex <root exception name/message>}
            ;; {:err string} prints to stderr
            :else
            (when-some [result (worker/repl-eval worker ::stdin read-result)]
              (case (:type result)
                :repl/result
                (send msg result) ;; :value is already a edn string

                :repl/set-ns-complete
                nil

                :repl/require-complete
                nil

                :repl/interrupt
                nil

                :repl/timeout
                (send msg {:err "REPL command timed out.\n"})

                :repl/no-eval-target
                (send msg {:err "There is no connected JS runtime.\n"})

                :repl/too-many-eval-clients
                (send msg {:err "There are too many JS runtimes, don't know which to eval in.\n"})

                :else
                (send msg {:value (pr-str [:FIXME result])}))

              (recur))
            ))))

    (send msg {:status :done})
    ))

(defn cljs-select [next]
  (fn [{:keys [session op] :as msg}]
    (let [repl-var #'api/*nrepl-cljs*]
      (when-not (contains? @session repl-var)
        (swap! session assoc repl-var nil))

      (let [build-id
            (get @session repl-var)

            worker
            (when build-id
              (api/get-worker build-id))]

        (prn [:cljs-select op build-id (some? worker) (keys msg)])
        (when (= op "eval")
          (println)
          (println (:code msg))
          (println)
          (flush))
        (-> msg
            (cond->
              worker
              (assoc ::worker worker ::build-id build-id))
            (next)
            )))))

(middleware/set-descriptor!
  #'cljs-select
  {:requires
   #{"clone"}

   :expects
   #{}

   :handles
   {}})

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
   #{"eval"}

   :handles
   {"cljs/select" {}}})

;; rewrite piggieback descriptor so it always runs after select
(middleware/set-descriptor!
  #'cemerick.piggieback/wrap-cljs-repl
  {:requires #{#'cljs-select}
   :expects #{}
   :handles {}})

(defn make-middleware-stack []
  (-> [#'clojure.tools.nrepl.middleware/wrap-describe
       #'clojure.tools.nrepl.middleware.interruptible-eval/interruptible-eval
       #'clojure.tools.nrepl.middleware.load-file/wrap-load-file


       ;; FIXME: insert any other middleware here

       ;; provided by fake-piggieback, only because tools expect piggieback
       #'cemerick.piggieback/wrap-cljs-repl

       ;; cljs support
       #'cljs-eval
       #'cljs-select

       #'clojure.tools.nrepl.middleware.session/add-stdin
       #'clojure.tools.nrepl.middleware.session/session]
      (middleware/linearize-middleware-stack)))

(defn start
  [{:keys [host port]
    :or {host "localhost"
         port 0}
    :as config}]

  (let [middleware-stack
        (make-middleware-stack)

        handler-fn
        ((apply comp (reverse middleware-stack)) server/unknown-op)]

    (server/start-server
      :bind host
      :port port
      :handler handler-fn)))

(comment
  (prn (server/default-handler)))

(defn stop [server]
  (server/stop-server server))