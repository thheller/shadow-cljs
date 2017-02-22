(ns shadow.devtools.server.compiler.node-script
  (:refer-clojure :exclude (flush))
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.node :as node]
            [shadow.devtools.server.compiler :as comp]))

(defn init [state mode config]
  (node/configure state config))

(defn flush [state mode config]
  (case mode
    :dev
    (node/flush-unoptimized state)
    :release
    (node/flush-optimized state)))

(defmethod comp/process :node-script
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))