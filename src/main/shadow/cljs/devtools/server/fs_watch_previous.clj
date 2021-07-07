(ns shadow.cljs.devtools.server.fs-watch-previous
  (:require [shadow.build.api :as cljs]
            [clojure.core.async :as async :refer (alt!! thread >!!)]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.system-bus :as system-bus]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [shadow.build.resource :as rc])
  (:import (shadow.util FileWatcher)
           (java.io File)))

(defn service? [x]
  (and (map? x)
       (::service x)))

(defn poll-changes [{:keys [dir ^FileWatcher watcher]}]
  (let [changes (.pollForChanges watcher)]
    (when (seq changes)
      (->> changes
           (map (fn [[name event]]
                  {:dir dir
                   :name (rc/normalize-name name)
                   :ext (when-let [x (str/last-index-of name ".")]
                          (subs name (inc x)))
                   :file (io/file dir name)
                   :event event}))
           ;; ignore empty files
           (remove (fn [{:keys [event ^File file] :as x}]
                     (and (not= event :del)
                          (zero? (.length file)))))
           ))))

(defn watch-loop
  [watch-dirs control publish-fn]

  (loop []
    (alt!!
      control
      ([_]
       :terminated)

      (async/timeout 500)
      ([_]
       (let [fs-updates
             (->> watch-dirs
                  (mapcat poll-changes)
                  (into []))]

         (when (seq fs-updates)
           (publish-fn fs-updates))

         (recur)))))

  ;; shut down watchers when loop ends
  (doseq [{:keys [^FileWatcher watcher]} watch-dirs]
    (.close watcher))

  ::shutdown-complete)

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
     :thread (thread (watch-loop watch-dirs control publish-fn))}))

(defn stop [{:keys [control thread] :as svc}]
  {:pre [(service? svc)]}
  (async/close! control)
  (async/<!! thread))

