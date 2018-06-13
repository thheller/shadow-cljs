(ns shadow.build.targets.azure-fn
  (:require
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [clojure.spec.alpha :as s]
   [clojure.java.io :as io]
   [clojure.data.json :as json]
   [shadow.build :as build]
   [shadow.build.api :as build-api]
   [shadow.build.targets.shared :as shared]
   [shadow.build.targets.node-library :as node-lib]
   [shadow.build.config :as config]
   [cljs.compiler :as cljs-comp]
   [shadow.build.data :as data]
   [shadow.build.npm :as npm]))

(s/def ::fn-map (s/map-of keyword? shared/unquoted-qualified-symbol?))

(s/def ::runtime-dir shared/non-empty-string?)

(s/def ::target
  (s/keys
   :req-un
   [::fn-map
    ::runtime-dir]))

(defmethod config/target-spec :azure-fn [_]
  (s/spec ::target))


(defn generate-index-source
  [fn-ns fn-name]

  "module.exports = blax"
  )

(defn make-index-resource
  [output-dir fn-ns fn-name]
  (let [rc-id (str fn-name "$index.js")]
    {:resource-id [::fn-index rc-id]
     :resource-name rc-id
     :output-name "index.js"
     :type :js
     :provides #{}
     :last-modified (System/currentTimeMillis)
     :cache-key [(System/currentTimeMillis)]
     :requires #{fn-ns}
     :source (generate-index-source fn-ns fn-name)}))

(defn enrich-fn-map
  [output-dir [fn-name fn-var]]
  {:pre [(s/assert shared/unquoted-qualified-symbol? fn-var)]}
  (let [fn-name (name fn-name)
        fn-ns (-> fn-var namespace symbol)
        fn-sym (-> fn-var name symbol)]
    {:fn-name fn-name
     :fn-var fn-var
     :fn-ns fn-ns
     :fn-sym fn-sym
     :fn-json (io/file output-dir fn-name "function.json")
     :fn-rc (make-index-resource output-dir fn-ns fn-name)}))

(defn do-configure [state mode {:keys [fn-map runtime-dir output-dir] :as config}]
  (let [fn-maps
        (mapv (partial enrich-fn-map output-dir) fn-map)]

    (->
     ;; (reduce data/add-source state (map :fn-rc fn-maps)) ;; Thomas says we don't need this
     (assoc ::fn-maps fn-maps)
     (assoc ::output-dir (io/file output-dir runtime-dir))
     (assoc-in [::build/config :devtools :enabled] false)
     (update ::build/config merge {:exports fn-map
                                   :output-to (io/file output-dir runtime-dir "index.js")}))))

(comment
  (def m {:build-id :az
          :target :azure-fn
          :function-vars '[ep-cloud.init-store/http-handler
                           ep-cloud.query-events/http-handler]
          :runtime-dir "azure-fn"
          :output-dir "azure-fn"})

  (shadow.cljs.devtools.api/compile* m :az {:verbose true})
  )

(defn assoc-fn-data
  [{::keys [output-dir] :as state} {::keys [fn-ns fn-sym] :as fn-map}]
  (let [fn-meta (get-in state [:compiler-env :cljs.analyzer/namespaces fn-ns :defs fn-sym :meta])]
    (assoc fn-map
           :fn-data
           (reduce-kv
            (fn [m k v]
              (if-not (and (keyword? k) (= "azure" (namespace k)))
                m
                (assoc m (name k) v)))
            {}
            fn-meta))))

(defn do-flush [{::keys [output-dir] :as state} mode _]
  (let [fn-maps (map (partial assoc-fn-data state) (::fn-maps state))
        _ (pprint/pprint fn-maps)
        npm-requires
        (->> (data/get-build-sources state)
             (filter :js-require)
             (map :js-require)
             (map npm/split-package-require)
             (map first)
             (into #{}))

        ;; npm-input
        ;; (-> (io/file "package.json")
        ;;     (slurp)
        ;;     (json/read-str))

        ;; npm-deps
        ;; (reduce
        ;;  (fn [m dep]
        ;;    (assoc m dep (get-in npm-input ["dependencies" dep])))
        ;;  {}
        ;;  npm-requires)


        ]

    ;; generating folders
    (run! (comp io/make-parents :fn-json) fn-maps)

    ;; spitting function.json

    (run! (fn [{:keys [fn-json fn-data]}]
            ;; TODO caching
            (spit fn-json (json/write-str fn-data)))
          fn-maps)

    (assoc state ::fn-maps fn-maps)))

(defn process
  [{::build/keys [mode stage config] :as state}]
  (-> state
      (cond->
          (= :configure stage)
        (do-configure mode config)
        (= :flush stage)
        (do-flush mode config))
      (node-lib/process)))
