(ns shadow.cljs.devtools.server.reload-npm
  "service that watches fs updates and ensures npm resources are updated
   will emit system-bus messages for inform about changed resources"
  (:require [clojure.core.async :as async :refer (alt!! thread)]
            [shadow.jvm-log :as log]
            [shadow.build.npm :as npm]
            [shadow.cljs.model :as m]
            [clojure.set :as set]))

(defn dissoc-all [m files]
  (reduce dissoc m files))

(defn was-modified? [{:keys [file last-modified]}]
  ;; deleted or modified
  (or (not (.exists file))
      (not= last-modified (.lastModified file))))

(defn invalidate-files [index modified-files]
  (update index :files dissoc-all modified-files))

(defn check-files! [{:keys [index-ref] :as npm} update-fn]
  ;; this only needs to check files that were already referenced in some build
  ;; new files will be discovered when resolving

  (let [{:keys [files] :as index}
        @index-ref

        modified-resources
        (->> (:files @index-ref)
             (vals)
             (filter was-modified?)
             (into []))

        modified-files
        (into [] (map :file) modified-resources)]

    (when (seq modified-resources)

      (log/debug ::npm-update {:file-count (count files)
                               :modified-count (count modified-files)})

      ;; remove from cache
      (swap! index-ref invalidate-files modified-files)

      (let [modified-provides
            (->> modified-resources
                 (map :provides)
                 (reduce set/union #{}))]

        (update-fn {:added #{} :namespaces modified-provides})))))

(defn watch-loop [npm control-chan update-fn]
  (loop []
    (alt!!
      control-chan
      ([_] :stop)

      ;; FIXME: figure out how much CPU this uses
      ;; this is mostly watching node_modules which is unlikely to change
      ;; but JS modules usually contain a whole bunch of files
      ;; so increasing this would be fine
      (async/timeout 2000)
      ([_]
        (try
          (check-files! npm update-fn)
          (catch Exception e
            (log/warn-ex e ::npm-check-ex)))
        (recur))))

  ::terminated)

(defn start [npm update-fn]
  {:pre [(npm/service? npm)
         (fn? update-fn)]}
  (let [control-chan (async/chan)]
    {:npm npm
     :control-chan control-chan
     :update-fn update-fn
     :watch-thread (thread (watch-loop npm control-chan update-fn))}))

(defn stop [{:keys [watch-thread control-chan]}]
  (async/close! control-chan)
  (async/<!! watch-thread))

