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
    [shadow.build.macros :as bm])
  (:import [com.google.javascript.jscomp.deps ModuleNames]))

;; FIXME: rewrite this similar to npm-update so that it only checks files that are actually used
;; checking all source paths all the time is overkill

(def cljs-file-exts
  #{"cljs"
    "cljc"
    "js"})

(def macro-file-exts
  #{"clj"
    "cljc"})

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

        add-ns
        (fn [result event ns]
          (-> result
              (update :namespaces conj ns)
              (update event conj ns)))

        ns-updates
        (reduce
          (fn [result {:keys [event name]}]
            (-> result
                (add-ns event (util/filename->ns name))
                ;; JS file might be old style goog.provide/goog.module with regular js
                ;; or ESM with generic name, so add both. engine will look at the file
                ;; later and find the correct one
                (cond->
                  (util/is-js-file? name)
                  (add-ns event (symbol (ModuleNames/fileToModuleName name))))))
          {:namespaces #{}
           :mod #{}
           :del #{}
           :new #{}}
          cljs-updates)

        update-msg
        {:namespaces (:namespaces ns-updates)
         :deleted (:del ns-updates)
         :updated (:mod ns-updates)
         :added (:new ns-updates)
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
              (seq (:namespaces ns-updates)))
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

