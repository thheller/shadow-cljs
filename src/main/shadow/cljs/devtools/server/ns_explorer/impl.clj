(ns shadow.cljs.devtools.server.ns-explorer.impl
  (:require [clojure.core.async :as async :refer (>!!)]
            [shadow.jvm-log :as log]
            [shadow.build.api :as build-api]
            [clojure.java.io :as io]
            [cljs.analyzer :as cljs-ana]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.build.log :as build-log]))

(defmulti do-control (fn [state msg] (:op msg))
  :default ::default)

(defn ensure-build-state
  [{:keys [build-state] :as state}]
  (if build-state
    state
    (let [{:keys [server-config npm babel classpath build-executor]}
          state

          build-state
          (-> (build-api/init)
              (build-api/with-npm npm)
              (build-api/with-babel babel)
              (build-api/with-classpath classpath)
              (build-api/with-cache-dir (io/file (:cache-root server-config) "ns-explorer" "cache"))
              (build-api/with-executor build-executor)
              (build-api/with-logger
                (reify
                  build-log/BuildLog
                  (log* [_ state msg]
                    (log/debug ::build-log msg))))
              (build-api/with-js-options {:js-provider :require})
              (assoc :mode :dev))]

      (assoc state :build-state build-state)
      )))

(defmethod do-control ::default [state msg]
  (log/warn ::invalid-msg msg)
  (when-let [reply-to (:reply-to msg)]
    (async/close! reply-to))
  state)

(defmethod do-control ::get-ns-data [state {:keys [ns-sym reply-to]}]
  (try
    (let [{:keys [build-state] :as state}
          (ensure-build-state state)

          [build-sources resolved-state]
          (build-api/resolve-entries build-state [ns-sym])

          {:keys [compiler-env] :as compiled-state}
          (build-api/compile-sources resolved-state build-sources)]

      (>!! reply-to {:ns-sym ns-sym
                     :ns-info (get-in compiler-env [::cljs-ana/namespaces ns-sym])
                     :ns-sources build-sources})
      (assoc state :build-state compiled-state))
    (catch Exception e
      (log/warn-ex e ::get-ns-data {:ns-sym ns-sym})
      state)
    (finally
      (async/close! reply-to))))


