(ns shadow.cljs.devtools.server.reload-macros
  (:require [clojure.core.async :as async :refer (thread alt!!)]
            [clojure.tools.logging :as log]
            [shadow.build.macros :as m]
            [clojure.java.io :as io]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            ))

(defn root-resource [lib]
  (.. (name lib) (replace \- \_) (replace \. \/)))

(defn macro-ns-modified? [ns-sym last-loaded]
  (let [file-root
        (root-resource ns-sym)

        rc-url
        (or (io/resource (str file-root ".clj"))
            (io/resource (str file-root ".cljc")))]

    (cond
      ;; FIXME: deleted macro files?
      (not rc-url)
      (do (log/warnf "could not find macro resource: %s" ns-sym)
          false)

      ;; do not reload macros from jars, only files
      (not= "file" (.getProtocol rc-url))
      false

      :else
      (let [rc-mod
            (-> (.openConnection rc-url)
                (.getLastModified))]
        (> rc-mod last-loaded))
      )))

(defn check-macros! [system-bus]
  (let [active-macros @m/active-macros-ref

        reloaded
        (reduce-kv
          (fn [updated ns-sym last-loaded]
            (if-not (macro-ns-modified? ns-sym last-loaded)
              updated
              (locking m/require-lock
                ;; always update timestamp so it doesn't reload failing macros constantly
                (swap! m/active-macros-ref assoc ns-sym (System/currentTimeMillis))
                (try
                  (require ns-sym :reload)
                  (conj updated ns-sym)

                  (catch Exception e
                    (log/warnf e "macro reload failed: %s" ns-sym)
                    updated)))))
          #{}
          active-macros)]

    (when (seq reloaded)
      (log/warnf "macro namespace reloaded %s" reloaded)
      (sys-bus/publish! system-bus ::sys-msg/macro-update {:macro-namespaces reloaded}))

    ))

(defn watch-loop [system-bus control-chan]
  (loop []
    (alt!!
      control-chan
      ([_] :stop)

      (async/timeout 1000)
      ([_]
        (try
          (check-macros! system-bus)
          (catch Exception e
            (log/warn e "checking macros failed")))
        (recur))))

  ::terminated)

(defn start [system-bus]
  (let [control-chan
        (async/chan)]

    {:system-bus system-bus
     :control-chan control-chan
     :watch-thread (thread (watch-loop system-bus control-chan))}))


(defn stop [{:keys [watch-thread control-chan]}]
  (async/close! control-chan)
  (async/<!! watch-thread))
