(ns shadow.cljs.devtools.server.reload-classpath
  "service that watches fs updates and ensures classpath resources are updated
   will emit system-bus messages for inform about changed resources"
  (:require
    [clojure.core.async :as async :refer (alt!! thread)]
    [clojure.set :as set]
    [shadow.jvm-log :as log]
    [shadow.build.classpath :as cp]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs.model :as m]
    [shadow.cljs.util :as util]
    [shadow.build.resource :as rc]
    [shadow.build.macros :as bm]))

;; FIXME: rewrite this similar to npm-update so that it only checks files that are actually used
;; checking all source paths all the time is overkill

(def cljs-file-exts
  #{"cljs"
    "cljc"
    "js"})

(def macro-file-exts
  #{"clj"
    "cljc"})

(defn process-update
  [{:keys [classpath] :as state} {:keys [event name file dir ext] :as fs-update}]
  (try
    (log/debug ::classpath-update fs-update)

    (case event
      :mod
      (cp/file-update classpath dir file)
      :new
      (cp/file-add classpath dir file)
      :del
      (cp/file-remove classpath dir file))

    (catch Exception e
      (log/warn-ex e ::update-failed fs-update))))

(defn process-updates [{:keys [system-bus classpath] :as state} updates]
  (let [cljs-updates
        (->> updates
             (filter #(contains? cljs-file-exts (:ext %)))
             (into []))

        ;; must check macros here since otherwise there are 2 separate watchers
        ;; that may trigger at different times and leading builds to recompile
        ;; twice whenever a .cljc file with macros is modified
        updated-macros
        (->> updates
             (filter #(contains? macro-file-exts (:ext %)))
             (map :name)
             (map util/clj-name->ns)
             (filter #(contains? @bm/reloadable-macros-ref %))
             (into #{}))

        ;; classpath is treated as an external process so knowing what updates
        ;; actually did is kind of tough
        ;; fs events occur in weird order as well, just saving a file on windows
        ;; produces del -> new -> mod
        ;; new might be an empty file so it doesn't provide anything
        ;; so the most reliable option seems to be comparing which provides were added, updated, removed?

        provides-before
        (cp/get-provided-names classpath)

        _ (run! #(process-update state %) cljs-updates)

        provides-after
        (cp/get-provided-names classpath)

        provides-deleted
        (into #{} (remove provides-after) provides-before)

        provides-new
        (into #{} (remove provides-before) provides-after)

        provides-updated
        (->> cljs-updates
             (map :name)
             (map rc/normalize-name)
             (map #(cp/find-resource-by-name classpath %))
             ;; :del will have removed the resource so we can no longer find it
             ;; but we want to know which provides where deleted so we this is done
             ;; above
             (remove nil?)
             (mapcat :provides)
             (into #{}))

        update-msg
        {:namespaces (set/union provides-updated provides-new provides-deleted)
         :deleted provides-deleted
         :updated provides-updated
         :added provides-new
         :macros updated-macros}]

    ;; FIXME: this should be somehow coordinated with the workers
    ;; don't want to do it in the worker since that would mean loading things
    ;; twice if 2 builds are running. this already only reloads macros that
    ;; are already active
    (doseq [ns-sym updated-macros]
      (locking bm/require-lock
        (try
          (require ns-sym :reload)
          (catch Exception e
            ;; FIXME: better reporting for this!
            (log/warn-ex e ::macro-reload-ex {:ns-sym ns-sym})))))

    (when (or (seq updated-macros)
              (seq provides-deleted)
              (seq provides-updated)
              (seq provides-new))
      (log/debug ::m/resource-update update-msg)
      (sys-bus/publish! system-bus ::m/resource-update update-msg))

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

    (sys-bus/sub system-bus ::m/cljs-watch watch-chan true)

    {:system-bus system-bus
     :classpath classpath
     :control-chan control-chan
     :watch-chan watch-chan
     :watch-thread (thread (watch-loop system-bus classpath control-chan watch-chan))}))


(defn stop [{:keys [watch-thread watch-chan control-chan]}]
  (async/close! control-chan)
  (async/close! watch-chan)
  (async/<!! watch-thread))

