(ns shadow.build.targets.azure-fn
  (:require
    [clojure.spec.alpha :as s]
    [clojure.java.io :as io]
    [clojure.data.json :as json]
    [shadow.build :as build]
    [shadow.build.targets.shared :as shared]
    [shadow.build.targets.node-library :as node-lib]
    [shadow.build.config :as config]
    [cljs.compiler :as cljs-comp]
    [shadow.build.data :as data]
    [shadow.build.npm :as npm]))

(s/def ::entry-fn shared/unquoted-qualified-symbol?)

(s/def ::target
  (s/keys
    :req-un
    [::entry-fn
     ::shared/output-dir]))

(defmethod config/target-spec :azure-fn [_]
  (s/spec ::target))

(defn do-configure [state mode {:keys [entry-fn output-dir] :as config}]
  (-> state
      (assoc ::output-dir (io/file output-dir))
      (assoc-in [:compiler-options :optimizations] :advanced)
      (assoc-in [::build/config :devtools :enabled] false)
      (update ::build/config merge {:exports-var entry-fn
                                    :output-to (str output-dir "/index.js")})
      ))

(defn do-flush [{::keys [output-dir] :as state} mode {:keys [entry-fn] :as config}]
  (let [fn-ns (-> entry-fn namespace symbol)
        fn-sym (-> entry-fn name symbol)

        fn-meta
        (get-in state [:compiler-env :cljs.analyzer/namespaces fn-ns :defs fn-sym :meta])

        fn-data
        (reduce-kv
          (fn [m k v]
            (if-not (and (keyword? k) (= "azure" (namespace k)))
              m
              (assoc m (name k) v)))
          {}
          fn-meta)

        fn-file
        (io/file output-dir "function.json")

        npm-requires
        (->> (data/get-build-sources state)
             (filter :js-require)
             (map :js-require)
             (map npm/split-package-require)
             (map first)
             (into #{}))

        npm-input
        (-> (io/file "package.json")
            (slurp)
            (json/read-str))

        npm-deps
        (reduce
          (fn [m dep]
            (assoc m dep (get-in npm-input ["dependencies" dep])))
          {}
          npm-requires)

        pkg-file
        (io/file output-dir "package.json")

        ;; FIXME: should this be generated or not?
        pkg-data
        {:private true
         ;; FIXME: where should it get version from?
         :version "1.0.0"
         :name (cljs-comp/munge entry-fn)
         :dependencies npm-deps}]

    (io/make-parents fn-file)
    (when (not= fn-data (::fn-data state))
      (spit fn-file (json/write-str fn-data)))

    (when (not= pkg-data (::pkg-data state))
      (spit pkg-file (json/write-str pkg-data)))

    (assoc state
      ::fn-data fn-data
      ::pkg-data pkg-data
      )))

(defn process
  [{::build/keys [mode stage config] :as state}]
  (-> state
      (cond->
        (= :configure stage)
        (do-configure mode config)
        (= :flush stage)
        (do-flush mode config))
      (node-lib/process)))
