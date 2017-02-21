(ns shadow.devtools.server.compiler.script
  (:refer-clojure :exclude (flush))
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.node :as node]
            [shadow.devtools.server.compiler :as comp]))

(defn init [state mode {:keys [dev] :as config}]
  (-> state
      (cond->
        (= :dev mode)
        (-> (cljs/enable-source-maps)
            (cljs/set-build-options
              {:optimizations :none
               :use-file-min false}))

        (= :release mode)
        (cljs/set-build-options
          {:optimizations :simple}))

      (node/configure config)))

(defn flush [state mode config]
  (case mode
    :dev
    (node/flush-unoptimized state)
    :release
    (node/flush-optimized state)))

(defmethod comp/process :library
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))