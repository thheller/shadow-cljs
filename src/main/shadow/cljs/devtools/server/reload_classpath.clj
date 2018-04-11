(ns shadow.cljs.devtools.server.reload-classpath
  "service that watches fs updates and ensures classpath resources are updated
   will emit system-bus messages for inform about changed resources"
  (:require [clojure.core.async :as async :refer (alt!! thread)]
            [clojure.tools.logging :as log]
            [shadow.build.classpath :as cp]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [shadow.cljs.util :as util]
            [shadow.build.resource :as rc]))


;; FIXME: rewrite this similar to npm-update so that it only checks files that are actually used
;; checking all source paths all the time is overkill

(def interesting-file-exts
  #{"cljs"
    "cljc"
    "js"})

(defn process-update
  [{:keys [classpath] :as state} {:keys [event name file dir ext] :as fs-update}]
  (try
    (log/debugf "classpath update [%s] %s" event file)

    (case event
      :mod
      (cp/file-update classpath dir file)
      :new
      (cp/file-add classpath dir file)
      :del
      (cp/file-remove classpath dir file))
    (catch Exception e
      (log/warn e "classpath update failed" file)))

  state)

(defn process-updates [{:keys [system-bus classpath] :as state} updates]
  (let [fs-updates
        (->> updates
             (filter #(contains? interesting-file-exts (:ext %)))
             (into []))

        _ (log/debugf "classpath updates total:%d" (count fs-updates))

        state
        (reduce process-update state fs-updates)

        resources
        (->> fs-updates
             (map :name)
             (map rc/normalize-name)
             (map #(cp/find-resource-by-name classpath %))
             ;; may have been filtered out
             (remove nil?)
             (map :resource-id)
             (distinct)
             (into []))]

    (sys-bus/publish! system-bus ::sys-msg/resource-update {:resources resources})

    state
    ))

(defn watch-loop [system-bus classpath control-chan watch-chan]
  ;; FIXME: state is not really needed?
  (loop [state {:system-bus system-bus
                :classpath classpath}]
    (alt!!
      control-chan
      ([_] :stop)

      watch-chan
      ([{:keys [updates] :as msg}]
        (when (some? msg)
          (-> state
              (process-updates updates)
              (recur))))))
  ::terminated)

(defn start [system-bus classpath]
  (let [watch-chan
        (async/chan)

        control-chan
        (async/chan)]

    (sys-bus/sub system-bus ::sys-msg/cljs-watch watch-chan true)

    {:system-bus system-bus
     :classpath classpath
     :control-chan control-chan
     :watch-chan watch-chan
     :watch-thread (thread (watch-loop system-bus classpath control-chan watch-chan))}))


(defn stop [{:keys [watch-thread watch-chan control-chan]}]
  (async/close! control-chan)
  (async/close! watch-chan)
  (async/<!! watch-thread))

