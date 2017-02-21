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
          (cljs/compile-all-for-src state source-name)]

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
                ;; race condition accessing the cache file when this starts up in parallel to something else
                :manifest-cache-dir
                (doto (io/file "target" "shadow-explorer" "manifest-cache")
                  (io/make-parents))
                :cache-dir
                (doto (io/file "target" "shadow-explorer" "cache")
                  (io/make-parents)))
              (cljs/find-resources-in-classpath)
              (cljs/finalize-config))
          {fs-updates do-fs-updates
           control do-control}
          {:do-shutdown
           (fn [state]
             ;; (prn [:closing-down-explorer])
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
