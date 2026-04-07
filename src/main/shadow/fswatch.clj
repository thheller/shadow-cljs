(ns shadow.fswatch
  (:import [java.io File]))

(defn get-impl-ns [config]
  (case (:impl config)
    :macos "shadow.fswatch.macos"
    :polling "shadow.fswatch.polling"
    "shadow.fswatch.jvm"))

(defn setup [sys-config]
  (let [impl-ns (get-impl-ns (:fs-watch sys-config))
        watcher-setup (requiring-resolve (symbol impl-ns "setup"))]
    (watcher-setup sys-config)))

(defn start [config directories file-exts publish-fn]
  {:pre [(every? #(instance? File %) directories)
         (coll? file-exts)
         (every? string? file-exts)]}

  (let [impl-ns (get-impl-ns config)
        watcher-start (requiring-resolve (symbol impl-ns "start"))
        watcher-stop (requiring-resolve (symbol impl-ns "stop"))]

    {:watcher (watcher-start config directories file-exts publish-fn)
     :watcher-stop watcher-stop}))

(defn stop [{:keys [watcher watcher-stop] :as svc}]
  (watcher-stop watcher))
