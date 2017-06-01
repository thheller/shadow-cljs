(ns shadow.cljs.devtools.targets.npm-module
  (:refer-clojure :exclude (flush require))
  (:require [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [shadow.cljs.output :as output]
            [shadow.cljs.devtools.targets.shared :as shared]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.devtools.targets.browser :as browser]))

(defn configure [state mode {:keys [runtime entries output-dir] :as config}]
  (-> state
      (assoc :source-map-comment false
             :module-format :js
             :emit-js-require true)

      (cond->
        output-dir
        (cljs/merge-build-options {:output-dir (io/file output-dir)})
        )))

(defn init [state mode {:keys [runtime entries output-dir] :as config}]
  (let [entries
        (or entries
            (->> (:sources state)
                 (vals)
                 (remove :from-jar)
                 (filter #(= :cljs (:type %)))
                 (map :provides)
                 (reduce set/union #{})))

        entries
        (conj entries 'cljs.core)]

    (-> state
        (cljs/configure-module :default entries #{} {:expand true})

        (cond->
          (= :dev mode)
          (repl/setup))

        (cond->
          (and (:worker-info state) (= :dev mode) (= :node runtime))
          (shared/inject-node-repl config)
          (and (:worker-info state) (= :dev mode) (or (= :browser runtime)
                                                      (nil? runtime)))
          (browser/inject-devtools config)
          ))))

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :init
    (init state mode config)

    :flush
    (case mode
      :dev
      (output/flush-dev-js-modules state mode config)
      :release
      (output/flush-optimized state))

    state
    ))
