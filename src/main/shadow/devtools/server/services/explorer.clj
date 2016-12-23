(ns shadow.devtools.server.services.explorer
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.repl :as repl]
            [shadow.devtools.server.services.fs-watch :as fs-watch]
            [clojure.core.async :as async :refer (thread alt!! <!! >!!)]
            [shadow.devtools.server.util :as util]))

(defn service? [svc]
  (and (map? svc)
       (::service svc)))

(defn- do-fs-updates [state msg]
  (-> state
      (cljs/reload-modified-files! msg)
      (cljs/finalize-config)))

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

(defn start [fs-watch]
  (let [fs-updates
        (async/chan)

        state-ref
        (volatile! nil)

        thread-ref
        (util/server-thread
          state-ref
          (-> (cljs/init-state)
              (assoc :logger util/null-log)
              (cljs/find-resources-in-classpath)
              (cljs/finalize-config))
          {fs-updates do-fs-updates}
          {})]

    (fs-watch/subscribe fs-watch fs-updates)

    {::service true
     :state-ref state-ref
     :fs-updates fs-updates
     :thread-ref thread-ref}
    ))

(defn stop [svc]
  {:pre [(service? svc)]}
  (async/close! (:fs-updates svc))
  (<!! (:thread-ref svc)))
