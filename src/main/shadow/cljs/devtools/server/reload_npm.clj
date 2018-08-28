(ns shadow.cljs.devtools.server.reload-npm
  "service that watches fs updates and ensures npm resources are updated
   will emit system-bus messages for inform about changed resources"
  (:require [clojure.core.async :as async :refer (alt!! thread)]
            [shadow.jvm-log :as log]
            [shadow.build.npm :as npm]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.model :as m]
            [shadow.cljs.util :as util]))

(defn dissoc-all [m files]
  (reduce dissoc m files))

(defn was-modified? [{:keys [file last-modified]}]
  ;; deleted or modified
  (or (not (.exists file))
      (not= last-modified (.lastModified file))))

(defn invalidate-files [index modified-files]
  (update index :files dissoc-all modified-files))

(defn check-files! [system-bus {:keys [index-ref] :as npm}]
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
            (into #{} (map :provides) modified-resources)]

        (sys-bus/publish! system-bus ::m/resource-update {:provides modified-provides})
        ))))

(defn watch-loop [system-bus npm control-chan]
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
          (check-files! system-bus npm)
          (catch Exception e
            (log/warn-ex e ::npm-check-ex)))
        (recur))))

  ::terminated)

(defn start [system-bus npm]
  (let [control-chan
        (async/chan)]

    {:system-bus system-bus
     :npm npm
     :control-chan control-chan
     :watch-thread (thread (watch-loop system-bus npm control-chan))}))


(defn stop [{:keys [watch-thread control-chan]}]
  (async/close! control-chan)
  (async/<!! watch-thread))

