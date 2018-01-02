(ns shadow.build.targets.node-library
  (:refer-clojure :exclude (flush))
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [shadow.build.api :as cljs]
            [shadow.build :as comp]
            [shadow.build.targets.shared :as shared]
            [shadow.build.config :as config]
            [shadow.cljs.repl :as repl]
            [shadow.build.node :as node]
            [shadow.cljs.util :as util]
            [shadow.build.data :as data]))

(s/def ::exports
  (s/or
    :fn
    shared/unquoted-qualified-symbol?

    :map
    (s/map-of
      keyword?
      shared/unquoted-qualified-symbol?)))

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
  {:pre [(data/build-state? state)
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
        {:resource-id [::helper "shadow/umd_helper.cljs"]
         :resource-name "shadow/umd_helper.cljs"
         :output-name "shadow.umd_helper.js"
         :type :cljs
         :provides #{'shadow.umd-helper}
         :requires requires
         :deps (into [] requires)
         :ns 'shadow.umd-helper
         :source [`(~'ns ~'shadow.umd-helper
                     (:require ~@(mapv vector entries)))
                  `(defn ~(with-meta 'get-exports {:export true}) [] ~get-exports)]
         :cache-key (System/currentTimeMillis)
         :last-modified (System/currentTimeMillis)
         :virtual true}

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
        (-> (slurp (io/resource "shadow/build/targets/umd_exports.txt"))
            (str/split #"//CLJS-HERE"))]

    (-> state
        (assoc :node-config node-config)
        (data/add-source umd-helper)
        (cljs/configure-modules
          {:main
           {:entries '[shadow.umd-helper]
            :depends-on #{}
            :prepend prepend
            :append append}}))))

(defn check-exports!
  [{:keys [compiler-env] :as state}
   {:keys [exports] :as config}]

  (doseq [[export-name export-sym] exports]
    (let [export-ns (symbol (namespace export-sym))
          export-fn (symbol (name export-sym))]

      (when-not (get-in compiler-env [:cljs.analyzer/namespaces export-ns :defs export-fn])
        (throw (ex-info (format "The export %s as %s does not exist!" export-sym export-name)
                 {:tag ::export-not-found
                  :export-name export-name
                  :export-sym export-sym})))))

  state)

(defn configure [state mode {:keys [id] :as config}]
  (-> state
      (cond->
        (and (= :release mode)
             (nil? (get-in config [:compiler-options :optimizations])))
        (cljs/merge-compiler-options {:optimizations :simple}))

      ;; node builds should never attempt to import libs through closure
      (assoc-in [:js-options :js-provider] :require)

      (shared/set-output-dir mode config)
      (create-module config)

      (cond->
        (:worker-info state)
        (-> (repl/setup)
            (shared/inject-node-repl config)))))

(defn flush [state mode config]
  (case mode
    :dev
    (node/flush-unoptimized state)
    :release
    (node/flush-optimized state)))

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :compile-finish
    (check-exports! state config)

    :flush
    (flush state mode config)

    state
    ))