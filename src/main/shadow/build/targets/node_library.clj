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
  (s/map-of
    simple-keyword?
    shared/unquoted-qualified-symbol?))

(s/def ::exports-fn shared/unquoted-qualified-symbol?)

(s/def ::exports-var shared/unquoted-qualified-symbol?)

(s/def ::umd-root-name shared/non-empty-string?)

(s/def ::target
  (s/and
    (s/keys
      :req-un
      [::shared/output-to]
      :opt-un
      [::shared/output-dir
       ::exports
       ::exports-var
       ::exports-fn
       ::umd-root-name])
    (s/or
      :exports
      #(contains? % :exports)
      :exports-fn
      #(contains? % :exports-fn)
      :exports-sym
      #(contains? % :exports-sym))
    ))

(defmethod config/target-spec :node-library [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(defn create-module [state {:keys [exports exports-fn exports-var output-to umd-root-name] :as config}]
  {:pre [(data/build-state? state)
         (map? config)]}

  (let [[entries get-exports]
        (cond
          exports-var
          [#{(-> exports-var namespace symbol)}
           exports-var]

          exports-fn
          [#{(-> exports-fn namespace symbol)}
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
         :cache-key [(System/currentTimeMillis)]
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
            (str/split #"//CLJS-HERE"))

        prepend
        (cond-> prepend
          umd-root-name
          (str/replace #"root.returnExports" (str "root." umd-root-name)))]

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
   {:keys [exports exports-fn exports-var] :as config}]

  (doseq [[export-name export-sym]
          (cond
            exports-fn
            {:module.exports exports-fn}
            exports-var
            {:module.exports exports-var}
            (map? exports)
            exports)]

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
      ;; node builds should never attempt to import libs through closure
      (assoc-in [:js-options :js-provider] :require)
      (assoc-in [:compiler-options :closure-defines 'cljs.core/*target*] "nodejs")

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