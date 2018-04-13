(ns shadow.build.targets.react-native
  (:refer-clojure :exclude (flush))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cljs.compiler :as cljs-comp]
            [clojure.spec.alpha :as s]
            [shadow.cljs.repl :as repl]
            [shadow.build.node :as node]
            [shadow.build :as comp]
            [shadow.build.targets.shared :as shared]
            [shadow.build.config :as config]
            [shadow.build.api :as build-api]
            [shadow.build.modules :as modules]
            [shadow.build.output :as output]
            [clojure.java.io :as io]
            [shadow.build.data :as data]
            [shadow.cljs.util :as util]))

(s/def ::entry simple-symbol?)

(s/def ::target
  (s/keys
    :req-un
    [::entry
     ::shared/output-to]
    ))

(defmethod config/target-spec :react-native [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(defn configure [state mode {:keys [entry output-to] :as config}]
  (let [output-file (io/file output-to)
        output-dir (.getParentFile output-file)]

    (-> state
        (build-api/with-build-options
          {:output-dir output-dir
           :module-format :js})

        (assoc ::output-file output-file)

        (build-api/configure-modules
          {:index {:entries [entry]
                   :expand true}})

        (update :js-options merge {:js-provider :require})
        (assoc-in [:compiler-options :closure-defines 'cljs.core/*target*] "react-native")

        (cond->
          (:worker-info state)
          (-> (repl/setup)
              (update-in [::modules/config :index :entries] shared/prepend
                '[cljs.user
                  shadow.cljs.devtools.client.react-native]))))))

(defn flush [{::keys [output-file] :as state} mode {:keys [entry] :as config}]
  (case mode
    :dev
    (output/flush-dev-js-modules state mode config)
    :release
    (output/flush-optimized state))

  (spit output-file
    (str (when (:worker-info state)
           "require(\"./shadow.cljs.devtools.client.react_native\");\n")
         "module.exports = require(\"./" (cljs-comp/munge entry) "\");"))
  state
  )

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :flush
    (flush state mode config)

    state
    ))