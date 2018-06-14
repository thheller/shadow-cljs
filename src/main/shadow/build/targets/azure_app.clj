(ns shadow.build.targets.azure-app
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

(s/def ::fn-map
  (s/map-of simple-keyword? shared/unquoted-qualified-symbol?))

(s/def ::app-dir shared/non-empty-string?)

(s/def ::target
  (s/keys
    :req-un
    [::fn-map
     ::app-dir]))

(defmethod config/target-spec :azure-fn [_]
  (s/spec ::target))

(defn do-configure [state mode {:keys [fn-map app-dir] :as config}]
  (-> state
      (assoc ::app-dir (io/file app-dir)
             ::fn-data {}
             ::fn-map fn-map)
      (assoc-in [:compiler-options :optimizations] :advanced)
      (update ::build/config merge {:exports fn-map
                                    ;; FIXME: can't have a {:cljs some.ns/fn} function when using cljs dir
                                    :output-to (str app-dir "/cljs/shared.js")})
      ))

(defn flush-fn-file [{::keys [app-dir] :as state} fn-id azure-fn]
  (let [fn-ns (-> azure-fn namespace symbol)
        fn-sym (-> azure-fn name symbol)

        fn-id-s
        (-> fn-id name cljs-comp/munge)

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

        output-dir
        (io/file app-dir fn-id-s)

        fn-file
        (io/file output-dir "function.json")

        index-file
        (io/file output-dir "index.js")]

    (when (empty? fn-data)
      (throw (ex-info (format "azure fn %s did not define any azure metadata" azure-fn) {:fn-id fn-id :azure-fn azure-fn})))

    (io/make-parents fn-file)
    (when (not= fn-data (get-in state [::fn-data fn-id]))
      (spit fn-file (json/write-str fn-data))
      (spit index-file (str "module.exports = require(\"../cljs/shared.js\")." fn-id-s ";\n")))

    (assoc-in state [::fn-data fn-id] fn-data)))

(defn do-flush [{::keys [fn-map] :as state}]
  (reduce-kv flush-fn-file state fn-map))

(defn process
  [{::build/keys [mode stage config] :as state}]
  (-> state
      (cond->
        (= :configure stage)
        (do-configure mode config)
        (= :flush stage)
        (do-flush))
      (node-lib/process)))
