(ns shadow.devtools.server.services.explorer
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.repl :as repl]
            [shadow.devtools.server.services.fs-watch :as fs-watch]
            [clojure.core.async :as async :refer (thread alt!! <!! >!!)]
            [shadow.devtools.server.util :as util]
            [clojure.java.io :as io]
            [cljs.analyzer :as ana]
            ))

(defn service? [svc]
  (and (map? svc)
       (::service svc)))

(defn- do-fs-updates [state msg]
  (-> state
      (cljs/reload-modified-files! msg)
      (cljs/finalize-config)))

(defmulti do-control
  (fn [state {:keys [op]}]
    op))

(defn collect-ns-info [state ns]
  (let [rc
        (cljs/get-resource-for-provide state ns)

        env
        (:compiler-env state)

        ana
        (get-in env [::ana/namespaces ns])]

    ana
    ))

(defmethod do-control :ns-info
  [state {:keys [ns reply-to] :as msg}]

  (let [state
        (-> state
            (cljs/compile-all-for-ns ns))]

    (>!! reply-to {:ns ns
                   :ns-info (collect-ns-info state ns)})

    (async/close! reply-to)

    state))

(defn get-all-provides [svc]
  {:pre [(service? svc)]}
  (let [state @(:state-ref svc)]
    (->> (:provide->source state)
         (keys))))

(defn get-project-provides [svc]
  {:pre [(service? svc)]}
  (let [{:keys [sources] :as state}
        @(:state-ref svc)]
    (->> (vals sources)
         (remove :from-jar)
         (mapcat :provides)
         (into #{}))))

(defn get-project-tests
  "lists of test namespaces"
  [svc]
  {:pre [(service? svc)]}
  (let [{:keys [sources] :as state}
        @(:state-ref svc)]
    (->> (vals sources)
         (remove :from-jar)
         (filter cljs/has-tests?)
         (map :ns)
         (into #{}))))

(defn get-ns-info
  [{:keys [control] :as svc} ns]

  (let [reply-to
        (async/chan)]

    (>!! control {:op :ns-info
                  :ns ns
                  :reply-to reply-to})

    (let [result (<!! reply-to)]
      (prn [:result result])

      result

      )))


(defn start [fs-watch]
  (let [fs-updates
        (async/chan)

        control
        (async/chan)

        state-ref
        (volatile! nil)

        thread-ref
        (util/server-thread
          state-ref
          (-> (cljs/init-state)
              (assoc ;; :logger util/null-log
                     :cache-dir (io/file "target" "shadow-explorer"))
              (cljs/find-resources-in-classpath)
              (cljs/finalize-config))
          {fs-updates do-fs-updates
           control do-control}
          {:do-shutdown
           (fn [state]
             (prn [:closing-down-explorer])
             state)})]

    (fs-watch/subscribe fs-watch fs-updates)

    {::service true
     :state-ref state-ref
     :fs-updates fs-updates
     :control control
     :thread-ref thread-ref}
    ))

(defn stop [svc]
  {:pre [(service? svc)]}
  (async/close! (:fs-updates svc))
  (async/close! (:control svc))
  (<!! (:thread-ref svc)))
