(ns shadow.fswatch.jvm
  (:require
    [clojure.core.async :as async :refer (thread)]
    [shadow.fswatch.common :as common])
  (:import
    [shadow.fswatch FileWatcher]
    [java.io File]))

(defn service? [x]
  (and (map? x)
       (::service x)))

(defn setup [sys-config])

(defn start [config directories file-exts publish-fn]
  {:pre [(every? #(instance? File %) directories)
         (coll? file-exts)
         (every? string? file-exts)]}
  (let [control
        (async/chan)

        watch-dirs
        (->> directories
             (map (fn [^File dir]
                    {:dir dir
                     :watcher (FileWatcher/create dir (vec file-exts))}))
             (into []))]

    {::service true
     :control control
     :watch-dirs watch-dirs
     :thread (thread (common/watch-loop config watch-dirs control publish-fn))}))

(defn stop [{:keys [control thread] :as svc}]
  {:pre [(service? svc)]}
  (async/close! control)
  (async/<!! thread))

