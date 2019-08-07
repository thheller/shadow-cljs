(ns shadow.cljs.devtools.server.reload-macros
  (:require [clojure.core.async :as async :refer (thread alt!!)]
            [shadow.jvm-log :as log]
            [shadow.build.macros :as bm]
            [clojure.java.io :as io]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.model :as m]
            [shadow.cljs.util :as util]
            [clojure.set :as set]))

(defn clj-ns-modified? [clj-state-ref ns-sym]
  (let [rc-url (bm/find-macro-rc ns-sym)]

    (cond
      ;; FIXME: deleted macro files?
      (not rc-url)
      (do (log/warn ::macro-missing {:ns-sym ns-sym})
          (swap! bm/active-macros-ref dissoc ns-sym)
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

        clj-namespaces
        (reduce set/union #{} (vals active-macros))

        modified
        (into #{} (filter #(clj-ns-modified? clj-state-ref %)) clj-namespaces)

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
      (sys-bus/publish! system-bus ::m/macro-update {:macro-namespaces reloaded}))

    ))

(defn watch-loop [clj-state system-bus control-chan]
  (loop []
    (alt!!
      control-chan
      ([_] :stop)

      (async/timeout 1000)
      ([_]
        (try
          (check-macros! clj-state system-bus)
          (catch Exception e
            (log/warn-ex e ::macro-watch-ex)))
        (recur))))

  ::terminated)

(defn start [system-bus]
  (let [control-chan
        (async/chan)]

    {:system-bus system-bus
     :control-chan control-chan
     :watch-thread (thread (watch-loop (atom {}) system-bus control-chan))}))


(defn stop [{:keys [watch-thread control-chan]}]
  (async/close! control-chan)
  (async/<!! watch-thread))
