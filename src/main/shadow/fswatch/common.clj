(ns shadow.fswatch.common
  (:require
    [clojure.core.async :as async :refer (alt!! thread >!!)]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [shadow.build.resource :as rc])
  (:import
    [shadow.fswatch IFileWatcher]
    [java.io File]))

(defn service? [x]
  (and (map? x)
       (::service x)))

(defn poll-changes [{:keys [dir ^IFileWatcher watcher]}]
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
  [{:keys [loop-wait] :or {loop-wait 500}} watch-dirs control publish-fn]

  (loop []
    (alt!!
      control
      ([_]
       :terminated)

      (async/timeout loop-wait)
      ([_]
       (let [fs-updates
             (->> watch-dirs
                  (mapcat poll-changes)
                  (into []))]

         (when (seq fs-updates)
           (publish-fn fs-updates))

         (recur)))))

  ;; shut down watchers when loop ends
  (doseq [{:keys [^IFileWatcher watcher]} watch-dirs]
    (.close watcher))

  ::shutdown-complete)
