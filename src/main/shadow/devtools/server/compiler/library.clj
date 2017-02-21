(ns shadow.devtools.server.compiler.library
  (:refer-clojure :exclude (flush))
  (:require [shadow.cljs.build :as cljs]
            [shadow.cljs.umd :as umd]
            [shadow.devtools.server.compiler :as comp]))

(defn init [state mode {:keys [dev] :as config}]
  (let [{:keys [exports]} config]
    (-> state
        (cond->
          (= :dev mode)
          (-> (cljs/enable-source-maps)
              (cljs/set-build-options
                {:optimizations :none
                 :use-file-min false}))

          (= :release mode)
          (cljs/set-build-options
            {:optimizations :simple
             :pretty-print false}))

        (umd/create-module exports config))))

(defn flush [state mode config]
  (case mode
    :dev
    (umd/flush-unoptimized-module state)
    :release
    (umd/flush-module state)))

(defmethod comp/process :library
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))