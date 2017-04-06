(ns shadow.cljs.devtools.targets.node-script
  (:refer-clojure :exclude (flush))
  (:require [shadow.cljs.node :as node]
            [shadow.cljs.devtools.server.compiler :as comp]
            [shadow.cljs.devtools.targets.shared :as shared]
            [shadow.cljs.devtools.server.config :as config]
            [clojure.spec :as s]
            [shadow.cljs.build :as cljs]))

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

;; FIXME: should allow using :advanced
(defn init [state mode config]
  (-> state
      (cond->
        (= :release mode)
        (cljs/merge-compiler-options
          {:optimizations :simple}))
      (node/configure config)))

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