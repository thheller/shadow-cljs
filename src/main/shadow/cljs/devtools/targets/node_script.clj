(ns shadow.cljs.devtools.targets.node-script
  (:refer-clojure :exclude (flush))
  (:require [shadow.cljs.node :as node]
            [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.devtools.targets.shared :as shared]
            [shadow.cljs.devtools.config :as config]
            [clojure.spec.alpha :as s]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.repl :as repl]
            [clojure.set :as set]
            [clojure.string :as str]
            [cljs.compiler :as cljs-comp]))

(s/def ::main qualified-symbol?)

(s/def ::target
  (s/keys
    :req-un
    [::main
     ::shared/output-to]
    :opt-un
    [::shared/output-dir]
    ))

(defmethod config/target-spec :node-script [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))


;; FIXME: should allow using :advanced
(defn configure [state mode config]
  (-> state
      (cond->
        (and (= :release mode)
             (nil? (get-in config [:compiler-options :optimizations])))
        (cljs/merge-compiler-options {:optimizations :simple}))

      (shared/set-output-dir mode config)
      (node/configure config)))

(defn init [state mode {:keys [optimizations] :or {optimizations :simple} :as config}]
  (-> state
      (cond->
        (:worker-info state)
        (-> (repl/setup)
            (shared/inject-node-repl config)))))

(defn flush [state mode config]
  (case mode
    :dev
    (node/flush-unoptimized state)
    :release
    (node/flush-optimized state)))

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))