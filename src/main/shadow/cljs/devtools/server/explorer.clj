(ns shadow.cljs.devtools.server.explorer
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.repl :as repl]
            [clojure.core.async :as async :refer (thread alt!! <!! >!!)]
            [shadow.cljs.devtools.server.util :as util]
            [clojure.java.io :as io]
            [cljs.analyzer :as ana]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.worker.impl :as worker-impl]

            [clojure.tools.logging :as log]))

(defn service? [svc]
  (and (map? svc)
       (::service svc)))

(defn- do-cljs-watch [state {:keys [updates] :as msg}]
  (try
    (-> (reduce worker-impl/merge-fs-update state updates)
        (cljs/finalize-config))
    (catch Exception e
      (log/info ::fs-update-ex e)
      state)))

(defmulti do-control
  (fn [state {:keys [op]}]
    op))

(defn collect-source-info [state source-name]
  (let [{:keys [ns warnings] :as rc}
        (get-in state [:sources source-name])

        deps
        (cljs/get-deps-for-src state source-name)

        env
        (:compiler-env state)

        defs
        (->> (get-in env [::ana/namespaces ns :defs])
             (vals)
             (sort-by (fn [{:keys [name line]}]
                        [line name]))
             (map :name)
             (into []))]

    {:ns ns
     :warnings warnings
     :defs defs
     :deps deps}
    ))

(defmethod do-control :source-info
  [state {:keys [source-name reply-to] :as msg}]

  (try
    (let [state
          (-> state
              (cljs/reset-resource-by-name source-name)
              (cljs/compile-all-for-src source-name))]

      (>!! reply-to {:source-name source-name
                     :info (collect-source-info state source-name)})

      state)
    (catch Exception e
      (>!! reply-to {:source-name source-name
                     :error e})
      state
      )))

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

(defn get-project-sources
  "#{cljs/core.cljs foo/bar.cljs ...}"
  [svc]
  {:pre [(service? svc)]}
  (let [{:keys [sources] :as state}
        @(:state-ref svc)]
    (->> (vals sources)
         (remove :from-jar)
         (map :name)
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

(defn get-source-info
  [{:keys [control] :as svc} source-name]

  (let [reply-to (async/chan)]

    (>!! control {:op :source-info
                  :source-name source-name
                  :reply-to reply-to})

    (<!! reply-to)
    ))


(defn start [system-bus]
  (let [cljs-watch
        (async/chan)

        control
        (async/chan)

        state-ref
        (volatile! nil)

        thread-ref
        (util/server-thread
          state-ref
          (-> (cljs/init-state)
              (assoc ::worker-impl/compile-attempt 0)
              (as-> X
                (assoc X
                       ;; FIXME: log somewhere
                       :logger util/null-log
                       ;; race condition accessing the cache file when this starts up in parallel to something else
                       :manifest-cache-dir
                       (doto (io/file (:work-dir X) "shadow-cljs" "explorer" "manifest-cache")
                         (io/make-parents))
                       :cache-dir
                       (doto (io/file (:work-dir X) "shadow-cljs" "explorer" "cache")
                         (io/make-parents))))
              (cljs/find-resources-in-classpath)
              (cljs/finalize-config))
          {cljs-watch do-cljs-watch
           control do-control}
          {:do-shutdown
           (fn [state]
             ;; (prn [:closing-down-explorer])
             state)})]

    (sys-bus/sub system-bus ::sys-msg/cljs-watch cljs-watch)

    {::service true
     :system-bus system-bus
     :state-ref state-ref
     :cljs-watch cljs-watch
     :control control
     :thread-ref thread-ref}
    ))

(defn stop [{:keys [system-bus cljs-watch control] :as svc}]
  {:pre [(service? svc)]}
  (sys-bus/unsub system-bus ::sys-msg/cljs-watch cljs-watch)
  (async/close! cljs-watch)
  (async/close! control)
  (<!! (:thread-ref svc)))
