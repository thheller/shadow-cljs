(ns shadow.build.targets.npm-module
  (:refer-clojure :exclude (flush require))
  (:require [shadow.build :as comp]
            [shadow.build.api :as build-api]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [shadow.build.output :as output]
            [shadow.build.targets.shared :as shared]
            [shadow.build.classpath :as cp]
            [shadow.cljs.repl :as repl]
            [shadow.build.targets.browser :as browser]))

(defn configure [{:keys [classpath] :as state} mode {:keys [runtime entries output-dir] :as config}]
  (let [entries
        (or entries
            ;; if the user didn't specify any entries we default to compiling everything in the source-path (but not in jars)
            (cp/get-source-provides classpath))

        entries
        (conj entries 'cljs.core)

        module
        (-> {:entries entries
             :depends-on #{}
             :expand true}
            (cond->
              (and (:worker-info state) (= :dev mode) (or (= :browser runtime)
                                                          (nil? runtime)))
              (-> (browser/inject-repl-client state config)
                  (browser/inject-devtools-console state config))
              ))]

    (-> state
        (build-api/with-build-options
          {:module-format :js})
        (build-api/with-js-options
          {:js-provider :require})

        (build-api/configure-modules {:main module})

        (cond->
          output-dir
          (build-api/with-build-options {:output-dir (io/file output-dir)})

          (= :dev mode)
          (-> (repl/setup)
              (shared/merge-repl-defines config))

          ;; FIXME: do the rewriting like above for :browser
          (and (:worker-info state) (= :dev mode) (= :node runtime))
          (shared/inject-node-repl config)
          ))))

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :flush
    (case mode
      :dev
      (output/flush-dev-js-modules state mode config)
      :release
      (output/flush-optimized state))

    state
    ))
