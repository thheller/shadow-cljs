(ns shadow.devtools.server.services.fs-watch
  (:require [shadow.cljs.build :as cljs]
            [clojure.core.async :as async :refer (alt!! thread >!!)]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [shadow.devtools.server.util :as util])
  (:import (shadow.util FileWatcher)))


(defn service? [x]
  (and (map? x)
       (::service x)))

;; FIXME: make config option of shadow-build and re-use don't copy
(def classpath-excludes
  [#"resources(/?)$"
   #"classes(/?)$"
   #"java(/?)$"])

(defn watch-thread
  [control output]

  (loop [state
         (-> (cljs/init-state)
             (assoc :logger util/null-log)
             (cljs/find-resources-in-classpath))
         fs-seq 1]

    (alt!!
      control
      ([_]
        :terminated)

      (async/timeout 500)
      ([_]
        (let [modified
              (cljs/scan-for-modified-files state)

              ;; scanning for new files is expensive, don't do it that often
              modified
              (if (zero? (mod fs-seq 5))
                (concat modified (cljs/scan-for-new-files state))
                modified)]

          (if-not (seq modified)
            ;; no files changed, try again
            (recur state (inc fs-seq))

            ;; reload and notify
            ;; FIXME: everyone does the same thing here, this sucks
            ;; when 2 builds are running
            ;; this thread will reload all files (and macros)
            ;; so will each of the build processes
            (let [state
                  (cljs/reload-modified-files! state modified)]
              (>!! output modified)
              (recur state (inc fs-seq))
              ))))))

  ;; (log/info "watch-thread shutdown")

  ;; final value of the thread
  ::shutdown-complete)

(defn subscribe [{:keys [output-mult] :as svc} sub-chan]
  {:pre [(service? svc)]}
  (async/tap output-mult sub-chan true))

(defn start []
  (let [control
        (async/chan)

        output
        (async/chan (async/sliding-buffer 10))

        output-mult
        (async/mult output)]

    {::service true
     :control control
     :output output
     :output-mult output-mult
     :thread (thread (watch-thread control output))}))

(defn stop [svc]
  {:pre [(service? svc)]}
  (async/close! (:control svc)))

