(ns shadow.cljs.devtools.server.fs-watch
  (:require [shadow.cljs.build :as cljs]
            [clojure.core.async :as async :refer (alt!! thread >!!)]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.system-bus :as system-bus]
            [shadow.cljs.devtools.server.system-msg :as system-msg]
            [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as str])
  (:import (shadow.util FileWatcher)))


(defn service? [x]
  (and (map? x)
       (::service x)))

(defn poll-changes [{:keys [dir watcher]}]
  (let [changes (.pollForChanges watcher)]
    (when (seq changes)
      (->> changes
           (map (fn [[name event]]
                  {:dir dir
                   :name name
                   :ext (when-let [x (str/index-of name ".")]
                          (subs name (inc x)))
                   :file (io/file dir name)
                   :event event}))
           ))))

(defn watch-thread
  [watch-dirs control sys-bus topic]

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
            (system-bus/publish! sys-bus topic {:updates fs-updates}))

          (recur)))))

  ;; shut down watchers when loop ends
  (doseq [{:keys [watcher]} watch-dirs]
    (.close watcher))

  ::shutdown-complete)

(defn start [system-bus topic directories file-exts]
  (let [control
        (async/chan)

        watch-dirs
        (->> directories
             (map (fn [dir]
                    {:dir dir
                     :watcher (FileWatcher/create dir file-exts)}))
             (into []))]

    {::service true
     :control control
     :watch-dirs watch-dirs
     :thread (thread (watch-thread watch-dirs control system-bus topic))}))

(defn stop [{:keys [control thread] :as svc}]
  {:pre [(service? svc)]}
  (async/close! control)
  (async/<!! thread))


