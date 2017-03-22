(ns shadow.devtools.server.compiler.browser
  (:refer-clojure :exclude (flush))
  (:require [shadow.devtools.server.compiler :as comp]
            [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]))

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

(defn init [state mode {:keys [modules] :as config}]
  (let [{:keys [public-dir public-path]}
        (merge default-browser-config config)]

    (-> state
        (cond->
          public-path
          (cljs/merge-build-options {:public-path public-path})

          public-dir
          (cljs/merge-build-options {:public-dir (io/file public-dir)}))

        (configure-modules modules))))

(defn flush [state mode config]
  (case mode
    :dev
    (cljs/flush-unoptimized state)
    :release
    (cljs/flush-modules-to-disk state)))

(defn process
  [{::comp/keys [stage mode config] :as state}]
  (case stage
    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))
