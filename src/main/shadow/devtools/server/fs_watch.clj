(ns shadow.devtools.server.fs-watch
  (:require [shadow.cljs.build :as cljs]
            [clojure.core.async :as async :refer (alt!! thread >!!)]
            [shadow.devtools.server.util :as util]
            [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
            [clojure.string :as str])
  (:import (shadow.util FileWatcher)))


(defn service? [x]
  (and (map? x)
       (::service x)))

;; FIXME: make config option of shadow-build and re-use don't copy
(def classpath-excludes
  [#"resources(/?)$"
   #"classes(/?)$"
   #"java(/?)$"])

(defn poll-changes [{:keys [dir dir-type watcher]}]
  (let [changes (.pollForChanges watcher)]
    (when (seq changes)
      (->> changes
           (map (fn [[name event]]
                  {:dir dir
                   :dir-type dir-type
                   :name name
                   :ext (when-let [x (str/index-of name ".")]
                          (subs name (inc x)))
                   :file (io/file dir name)
                   :event event}))
           ))))

(defn watch-thread
  [watch-dirs control output]

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
            (>!! output fs-updates))

          (recur)))))

  ;; shut down watchers when loop ends
  (doseq [{:keys [watcher]} watch-dirs]
    (.close watcher))

  ::shutdown-complete)

(defn subscribe [{:keys [output-mult] :as svc} sub-chan]
  {:pre [(service? svc)]}
  (async/tap output-mult sub-chan true))

(defn get-watch-directories []
  (->> (cljs/classpath-entries)
       (remove #(cljs/should-exclude-classpath classpath-excludes %))
       (map io/file)
       (filter #(.isDirectory %))
       (map #(.getCanonicalFile %))
       (distinct)
       (into [])))

(defn start [{:keys [css-dirs] :as config}]
  (let [control
        (async/chan)

        output
        (async/chan (async/sliding-buffer 10))

        output-mult
        (async/mult output)

        css-dirs
        (->> css-dirs
             (map io/file)
             (filter #(.isDirectory %))
             (map (fn [dir]
                    {:dir dir
                     :dir-type :css
                     :watcher (FileWatcher/create dir ["css" "scss"])}))
             (into []))

        watch-dirs
        (->> (get-watch-directories)
             (map (fn [dir]
                    {:dir dir
                     :dir-type :classpath
                     :watcher (FileWatcher/create dir ["cljs" "cljc" "clj" "js"])}))
             (into css-dirs))]

    {::service true
     :control control
     :watch-dirs watch-dirs
     :output output
     :output-mult output-mult
     :thread (thread (watch-thread watch-dirs control output))}))

(defn stop [{:keys [control thread] :as svc}]
  {:pre [(service? svc)]}
  (async/close! control)
  (async/<!! thread))


