(ns shadow.devtools.server.compiler.browser
  (:refer-clojure :exclude (flush))
  (:require [shadow.devtools.server.compiler :as comp]
            [shadow.cljs.build :as cljs]))

(def default-browser-config
  {:public-dir "public/js"
   :public-path "/js"})

(defn- configure-modules
  [state modules]
  (reduce-kv
    (fn [state module-id {:keys [entries depends-on] :as module-config}]
      (cljs/configure-module state module-id entries depends-on module-config))
    state
    modules))

(defn- configure [state config]
  (let [{:keys [modules] :as config}
        (merge default-browser-config config)]
    ))

(defn init [state mode {:keys [modules] :as config}]
  (configure-modules state modules))

(defn flush [state mode config]
  (case mode
    :dev
    (cljs/flush-unoptimized state)
    :release
    (cljs/flush-modules-to-disk state)))

(defmethod comp/process :browser
  [{::comp/keys [stage mode config] :as state}]
  (case stage
    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))
