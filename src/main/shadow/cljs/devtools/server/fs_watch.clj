(ns shadow.cljs.devtools.server.fs-watch
  (:require [shadow.build.api :as cljs]
            [clojure.core.async :as async :refer (alt!! thread >!!)]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.system-bus :as system-bus]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [shadow.build.resource :as rc]
            [nextjournal.beholder :as beholder])
  (:import (java.io File)))

(defn service? [x]
  (and (map? x)
       (::service x)))

(defn watch-loop
  [watcher control]

  (loop []
    (alt!!
      control
      ([_]
        :terminated)))

  ;; shut down watchers when loop ends
  (beholder/stop watcher)

  ::shutdown-complete)

(defn start [config directories file-exts publish-fn]
  {:pre [(every? #(instance? File %) directories)
         (coll? file-exts)
         (every? string? file-exts)]}
  (let [control
        (async/chan)

        watcher
        (apply beholder/watch
               (fn file-changed [{:keys [type path] :as event}]
                 (let [file (.toFile path)
                       name (.getName file)
                       dir (-> file (.getAbsoluteFile) (.getParent))]
                   ;; ignore empty files
                   (when (or (= type :delete)
                             (pos? (.length file)))
                     (publish-fn {:dir   dir
                                  :name  (rc/normalize-name name)
                                  :ext   (when-let [x (str/last-index-of name ".")]
                                           (subs name (inc x)))
                                  :file  file
                                  :event event}))))
               (map #(.getCanonicalPath %) directories))]

    {::service true
     :control  control
     :watcher  watcher
     :thread   (thread (watch-loop watcher control))}))

(defn stop [{:keys [control thread] :as svc}]
  {:pre [(service? svc)]}
  (async/close! control)
  (async/<!! thread))
