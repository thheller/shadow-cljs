(ns shadow.cljs.devtools.targets.node-library
  (:refer-clojure :exclude (flush))
  (:require [clojure.spec :as s]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.umd :as umd]
            [shadow.cljs.devtools.server.compiler :as comp]
            [shadow.cljs.devtools.targets.shared :as shared]
            [shadow.cljs.devtools.server.config :as config]
            ))

(s/def ::exports
  (s/map-of
    keyword?
    qualified-symbol?))

(s/def ::target
  (s/keys
    :req-un
    [::exports
     ::shared/output-to]
    :opt-un
    [::shared/public-dir]
    ))

(defmethod config/target-spec :node-library [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(defn init [state mode {:keys [dev] :as config}]
  (let [{:keys [exports]} config]
    (-> state
        (cond->
          (= :release mode)
          (cljs/merge-compiler-options
            {:optimizations :simple}))
        (umd/create-module exports config))))

(defn flush [state mode config]
  (case mode
    :dev
    (umd/flush-unoptimized-module state)
    :release
    (umd/flush-module state)))

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))