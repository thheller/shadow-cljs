(ns shadow.devtools.targets.node-script
  (:refer-clojure :exclude (flush))
  (:require [shadow.cljs.node :as node]
            [shadow.devtools.server.compiler :as comp]
            [shadow.devtools.targets.shared :as shared]
            [shadow.devtools.server.config :as config]
            [clojure.spec :as s]))

(s/def ::main qualified-symbol?)

(s/def ::target
  (s/keys
    :req-un
    [::main
     ::shared/output-to]
    :opt-un
    [::shared/public-dir]
    ))

(defmethod config/target-spec :node-script [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(defn init [state mode config]
  (node/configure state config))

(defn flush [state mode config]
  (case mode
    :dev
    (node/flush-unoptimized state)
    :release
    (node/flush-optimized state)))

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))