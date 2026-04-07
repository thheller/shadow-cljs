(ns shadow.fswatch.polling
  (:require
    [clojure.core.async :as async :refer (thread)]
    [clojure.java.io :as io]
    [shadow.fswatch.common :as common])
  (:import
    [shadow.fswatch PollingFileWatcher]
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
                     :watcher (PollingFileWatcher. (-> dir (.getCanonicalFile) (.toPath)) (set file-exts))}))
             (into []))]

    {::service true
     :control control
     :watch-dirs watch-dirs
     :thread (thread (common/watch-loop config watch-dirs control publish-fn))}))

(defn stop [{:keys [control thread] :as svc}]
  {:pre [(service? svc)]}
  (async/close! control)
  (async/<!! thread))


(comment
  (setup {})
  (def w (start {}
           [(io/file "src/main")
            (io/file "src/test")]
           ["clj" "cljs" "cljc" "js"]
           prn))

  (stop w)
  )