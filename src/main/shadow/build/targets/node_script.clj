(ns shadow.build.targets.node-script
  (:refer-clojure :exclude (flush))
  (:require [cljs.compiler :as cljs-comp]
            [clojure.spec.alpha :as s]
            [shadow.cljs.repl :as repl]
            [shadow.build.node :as node]
            [shadow.build :as comp]
            [shadow.build.targets.shared :as shared]
            [shadow.build.config :as config]
            [shadow.build.api :as cljs]
            ))

(s/def ::main shared/unquoted-qualified-symbol?)

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

(defn configure [state mode config]
  (-> state
      (shared/set-output-dir mode config)

      (node/set-defaults)
      (node/configure config)

      (assoc-in [:compiler-options :closure-defines 'cljs.core/*target*] "nodejs")

      (cond->
        (:worker-info state)
        (shared/inject-node-repl config)

        (= :dev mode)
        (shared/inject-preloads :main config)
        )))

(defn check-main-exists! [{:keys [compiler-env node-config] :as state}]
  (let [{:keys [main main-ns main-fn]} node-config]
    (when-not (get-in compiler-env [:cljs.analyzer/namespaces main-ns :defs main-fn])
      (throw (ex-info (format "The configured main \"%s\" does not exist!" main)
               {:tag ::main-not-found
                :main-ns main-ns
                :main-fn main-fn
                :main main})))
    state))

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

    :compile-prepare
    (node/replace-goog-global state)

    :compile-finish
    (-> state
        (check-main-exists!)
        (cond->
          (shared/bootstrap-host-build? state)
          (shared/bootstrap-host-info)))

    :flush
    (flush state mode config)

    state
    ))