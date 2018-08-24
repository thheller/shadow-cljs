(ns shadow.cljs.devtools.server.ns-explorer
  "explorer for cljs analyzer data of the current classpath
   not tied to any build or REPL"
  (:require
    [clojure.core.async :as async :refer (<!! >!!)]
    [shadow.cljs.devtools.server.util :as util]
    [shadow.cljs.devtools.server.ns-explorer.impl :as impl]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.worker :as worker]
    ))

(defn service? [svc]
  (and (map? svc)
       (::service svc)))

(defn get-ns-data [{:keys [control] :as svc} ns-sym]
  (let [reply-to (async/chan 1)]
    (when (>!! control {:op ::impl/get-ns-data :ns-sym ns-sym :reply-to reply-to})
      (<!! reply-to))))

(defn start [server-config npm babel classpath build-executor]
  (let [control
        (async/chan 10) ;; FIXME: better strategy with timeouts? can't block forever when overloaded

        state-ref
        (volatile! nil)

        thread-ref
        (util/server-thread
          "shadow-cljs-explorer"
          state-ref
          {:server-config server-config
           :npm npm
           :babel babel
           :classpath classpath
           :build-executor build-executor}
          {control impl/do-control}
          {:do-shutdown
           (fn [state]
             ;; (prn [:closing-down-explorer])
             state)})]

    {::service true
     :state-ref state-ref
     :control control
     :thread-ref thread-ref}))

(defn stop [{:keys [control] :as svc}]
  {:pre [(service? svc)]}
  (async/close! control)
  (<!! (:thread-ref svc)))