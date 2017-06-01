(ns shadow.cljs.devtools.targets.node-library
  (:refer-clojure :exclude (flush))
  (:require [clojure.spec.alpha :as s]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.devtools.targets.shared :as shared]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.repl :as repl]
            [clojure.java.io :as io]
            [shadow.cljs.node :as node]
            [shadow.cljs.util :as util]
            [clojure.set :as set]
            [clojure.string :as str]))

(s/def ::exports
  (s/or
    :fn
    qualified-symbol?

    :map
    (s/map-of
      keyword?
      qualified-symbol?)))

(s/def ::target
  (s/keys
    :req-un
    [::exports
     ::shared/output-to]
    :opt-un
    [::shared/output-dir]
    ))

(defmethod config/target-spec :node-library [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(defn create-module [state {:keys [exports output-to] :as config}]
  {:pre [(util/compiler-state? state)
         (map? config)
         (or (qualified-symbol? exports)
             (and (map? exports)
                  (seq exports)))]}

  (let [[entries get-exports]
        (cond
          (qualified-symbol? exports)
          [#{(-> exports namespace symbol)}
           `(~exports)]

          (map? exports)
          [(->> exports
                (vals)
                (map namespace)
                (map symbol)
                (into #{}))
           `(cljs.core/js-obj ~@(->> exports (mapcat (fn [[k v]] [(name k) v]))))
           ])

        requires
        (conj entries 'cljs.core)

        umd-helper
        {:name "shadow/umd_helper.cljs"
         :js-name "shadow.umd_helper.js"
         :type :cljs
         :provides #{'shadow.umd-helper}
         :requires requires
         :require-order (into [] requires)
         :ns 'shadow.umd-helper
         :input (atom [`(~'ns ~'shadow.umd-helper
                          (:require ~@(mapv vector entries)))
                       `(defn ~(with-meta 'get-exports {:export true}) [] ~get-exports)])
         :last-modified (System/currentTimeMillis)}

        output-to
        (io/file output-to)

        output-name
        (.getName output-to)

        module-name
        (-> output-name (str/replace #".js$" "") (keyword))

        node-config
        {:output-to output-to}

        ;; based on https://github.com/umdjs/umd/blob/master/templates/returnExports.js
        [prepend append]
        (-> (slurp (io/resource "shadow/cljs/devtools/targets/umd_exports.txt"))
            (str/split #"//CLJS-HERE"))]

    (-> state
        (assoc :node-config node-config)
        (cljs/merge-resource umd-helper)
        (cljs/configure-module :umd '[shadow.umd-helper] #{}
          {:prepend prepend
           :append append}))))

(defn configure [state mode {:keys [id] :as config}]
  (-> state
      (cond->
        (= :release mode)
        (cljs/merge-compiler-options
          {:optimizations :simple}))

      (shared/set-output-dir mode config)
      (create-module config)))

(defn init [state mode {:keys [id] :as config}]
  (-> state
      (cond->
        (:worker-info state)
        (-> (repl/setup)
            (shared/inject-node-repl config)))))

(defn flush [state mode config]
  (node/flush-unoptimized state))

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))