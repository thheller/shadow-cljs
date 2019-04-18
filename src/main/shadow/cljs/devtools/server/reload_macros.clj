(ns shadow.cljs.devtools.server.reload-macros
  (:require [clojure.core.async :as async :refer (thread alt!!)]
            [shadow.jvm-log :as log]
            [shadow.build.macros :as bm]
            [clojure.java.io :as io]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.model :as m]
            [shadow.cljs.util :as util])
  (:import [java.net URLConnection]))

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
      (do (log/warn ::macro-missing {:ns-sym ns-sym})
          (swap! bm/active-macros-ref dissoc ns-sym)
          false)

      ;; do not reload macros from jars, only files
      (not= "file" (.getProtocol rc-url))
      false

      :else
      (> (util/url-last-modified rc-url) last-loaded)
      )))

(defn check-macros! [system-bus]
  (let [active-macros @bm/active-macros-ref

        reloaded
        (reduce-kv
          (fn [updated ns-sym last-loaded]
            (if-not (macro-ns-modified? ns-sym last-loaded)
              updated
              (locking bm/require-lock
                ;; always update timestamp so it doesn't reload failing macros constantly
                (swap! bm/active-macros-ref assoc ns-sym (System/currentTimeMillis))
                (try
                  (require ns-sym :reload)
                  (conj updated ns-sym)

                  (catch Exception e
                    (log/warn-ex e ::macro-reload-ex {:ns-sym ns-sym})
                    updated)))))
          #{}
          active-macros)]

    (when (seq reloaded)
      (log/debug ::macro-reload {:reloaded reloaded})
      (sys-bus/publish! system-bus ::m/macro-update {:macro-namespaces reloaded}))

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
            (log/warn-ex e ::macro-watch-ex)))
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
