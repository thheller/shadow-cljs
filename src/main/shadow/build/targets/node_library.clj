(ns shadow.build.targets.node-library
  (:refer-clojure :exclude (flush))
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [shadow.build.api :as cljs]
            [shadow.build :as comp]
            [shadow.build.targets.shared :as shared]
            [shadow.build.config :as config]
            [shadow.cljs.repl :as repl]
            [shadow.build.node :as node]
            [shadow.cljs.util :as util]
            [shadow.build.data :as data]
            [cljs.compiler :as cljs-comp]))

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
      :exports-var
      #(contains? % :exports-var))
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
           `(~exports-fn)]

          (map? exports)
          [(->> exports
                (vals)
                (map namespace)
                (map symbol)
                (into #{}))
           (if (= :release (:shadow.build/mode state))
             `(cljs.core/js-obj ~@(->> exports (mapcat (fn [[k v]] [(-> k (name) (cljs-comp/munge)) v]))))
             `(doto (cljs.core/js-obj)
                ~@(for [[k v] exports]
                    `(js/Object.defineProperty ~(-> k (name) (cljs-comp/munge))
                       (cljs.core/js-obj
                         "enumerable" true
                         "get" (fn [] ~v)) )))
             )])

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
                  `(defn ~'get-exports []
                     ~get-exports)]
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

        wrapper-rc
        (if (= :dev (:shadow.build/mode state))
          (io/resource "shadow/build/targets/umd_exports_dev.txt")
          (io/resource "shadow/build/targets/umd_exports.txt"))

        ;; based on https://github.com/umdjs/umd/blob/master/templates/returnExports.js
        [prepend append]
        (-> wrapper-rc
            (slurp )
            (str/split #"//CLJS-HERE"))

        prepend
        (-> (when-let [prep (:prepend config)]
              (str (str/trim prep) "\n"))
            (str (str/trim prepend) "\n")
            (cond->
              umd-root-name
              (str/replace #"root.returnExports" (str "root." umd-root-name))))]

    (-> state
        (assoc :node-config node-config)
        (data/add-source umd-helper)
        (cljs/configure-modules
          {:main
           {:entries '[shadow.umd-helper]
            :depends-on #{}
            :prepend prepend
            :append-js "\nshadow$umd$export = shadow.umd_helper.get_exports();\n"
            :append (str/trim append)}}))))

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
      (node/set-defaults)

      (assoc-in [:compiler-options :closure-defines 'cljs.core/*target*] "nodejs")

      (shared/set-output-dir mode config)
      (create-module config)

      (cond->
        (:worker-info state)
        (shared/inject-node-repl config)

        (= :dev mode)
        (shared/inject-preloads :main config)
        )))

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

    :compile-prepare
    (node/replace-goog-global state)

    :compile-finish
    (-> state
        (check-exports! config)
        (cond->
          (shared/bootstrap-host-build? state)
          (shared/bootstrap-host-info)))

    :flush
    (flush state mode config)

    state
    ))