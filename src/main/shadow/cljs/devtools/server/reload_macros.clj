(ns shadow.cljs.devtools.server.reload-macros
  (:require
    [shadow.jvm-log :as log]
    [shadow.build.macros :as bm]
    [clojure.java.io :as io]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs.model :as m]
    [shadow.cljs.util :as util]
    [clojure.set :as set])
  (:import [java.util.concurrent Executors TimeUnit]))

(defn clj-ns-modified? [clj-state-ref ns-sym]
  (let [rc-url (bm/find-macro-rc ns-sym)]

    (cond
      ;; a CLJ namespace may have been deleted and the reference
      ;; removed from the macro ns but the in memory version will
      ;; still have the reference to the "old" in memory version.
      ;; since I don't want to go messing with ns references
      ;; this just silently removes the active-macros-ref in case
      ;; it was an actual macro. if it was just a dependency of
      ;; a macro we do nothing since it would just re-discover
      ;; the dependency when the macro was modified.
      (not rc-url)
      (do (swap! bm/active-macros-ref dissoc ns-sym)
          false)

      ;; do not reload macros from jars, only files
      (not= "file" (.getProtocol rc-url))
      false

      :else
      (let [new-mod (util/url-last-modified rc-url)
            last-mod (get @clj-state-ref ns-sym)]
        (swap! clj-state-ref assoc ns-sym new-mod)

        ;; not modified if tracked before
        (and last-mod (not= new-mod last-mod))
        ))))

(defn check-macros! [clj-state-ref system-bus]
  (let [active-macros @bm/active-macros-ref

        modified
        (into #{} (filter #(clj-ns-modified? clj-state-ref %)) @bm/reloadable-macros-ref)

        ;; FIXME: this probably needs to reload macros in a proper dependency order
        ;; otherwise adding a function in on ns that another users may break
        ;; if the use is reloaded before the provider?
        ;; trying to avoid :reload-all since that may reload too much
        _
        (doseq [ns-sym modified]
          (locking bm/require-lock
            (try
              (require ns-sym :reload)
              (catch Exception e
                (log/warn-ex e ::macro-reload-ex {:ns-sym ns-sym})))))

        reloaded
        (reduce-kv
          (fn [reloaded ns-sym ns-deps]
            (if-not (some modified ns-deps)
              reloaded
              (conj reloaded ns-sym)))
          #{}
          active-macros)]

    (when (seq reloaded)
      (log/debug ::macro-reload {:reloaded reloaded})
      (sys-bus/publish! system-bus ::m/macro-update {:macro-namespaces reloaded})
      )))

(defn start [system-bus]
  (let [ex (Executors/newSingleThreadScheduledExecutor)

        state-ref
        (atom {})

        check-fn
        (fn []
          (try
            (check-macros! state-ref system-bus)
            (catch Exception e
              (log/warn-ex e ::macro-watch-ex))))]

    {:system-bus system-bus
     :ex ex
     :state-ref state-ref
     :fut (.scheduleWithFixedDelay ex check-fn 2 2 TimeUnit/SECONDS)
     }))

(defn stop [{:keys [ex]}]
  (.shutdown ex))