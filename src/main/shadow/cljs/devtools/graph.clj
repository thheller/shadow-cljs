(ns shadow.cljs.devtools.graph
  (:require
    [com.wsscode.pathom.connect :as pc]
    [com.wsscode.pathom.profile :as pp]
    [com.wsscode.pathom.core :as p]
    [shadow.build :as build]
    [shadow.build.classpath :as cp]
    [shadow.cljs.model :as m]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.devtools.server.util :as util]
    [shadow.cljs.devtools.server.ns-explorer :as ex]
    [shadow.jvm-log :as log]))

(defmulti resolver-fn pc/resolver-dispatch)

(def indexes (atom {}))

(def add-resolver (pc/resolver-factory resolver-fn indexes))

(defn adapt-build-config [{:keys [build-id target] :as config}]
  {::m/build-id build-id
   ::m/build-target target
   ::m/build-config-raw config})

(defn as-resource-info [rc]
  (dissoc rc :file :url :source :source-fn))

(def config-attrs
  [::m/build-id
   ::m/build-target
   ::m/build-config-raw])

(add-resolver `build-by-id
  {::pc/input #{::m/build-id}
   ::pc/output config-attrs}
  (fn [env {::m/keys [build-id] :as input}]
    (-> (api/get-build-config build-id)
        (adapt-build-config))
    ))

(add-resolver `build-resolve
  {::pc/input #{::m/build-id}
   ::pc/output [::m/build-sources]}
  (fn [env {::m/keys [build-id] :as input}]
    (let [{:keys [mode] :or {mode :release} :as params}
          (get-in env [:ast :params])

          build-config
          (api/get-build-config build-id)]

      {::m/build-sources
       (-> (util/new-build build-config mode {})
           (build/configure mode build-config)
           (build/resolve)
           :build-sources)})))

(add-resolver `build-configs
  {::pc/output [{::m/build-configs config-attrs}]}
  (fn [env _]
    (let [{:keys [builds] :as config}
          (config/load-cljs-edn)]

      {::m/build-configs
       (->> (vals builds)
            (map adapt-build-config)
            (into []))})))

(add-resolver `resource-by-name
  {::pc/input #{::m/resource-name}
   ::pc/output [::m/resource-info
                ::m/resource-ns]}
  (fn [{:keys [classpath ast] :as env} {::m/keys [resource-name] :as input}]
    (when-let [{:keys [ns] :as rc}
               (cp/find-resource-by-name classpath resource-name)]
      {::m/resource-ns ns
       ::m/resource-info (as-resource-info rc)})))

(add-resolver `resource-by-id
  {::pc/input #{::m/resource-id}
   ::pc/output [::m/resource-info
                ::m/resource-ns]}
  (fn [{:keys [classpath ast] :as env} {::m/keys [resource-id] :as input}]
    (when (vector? resource-id)
      (case (first resource-id)
        :shadow.build.classpath/resource
        (when-let [{:keys [ns] :as rc}
                   (cp/find-resource-by-name classpath (second resource-id))]
          {::m/resource-ns ns
           ::m/resource-info (as-resource-info rc)})
        (throw (ex-info "FIXME: resource-by-id" {:input input}))))))

(add-resolver `find-resource-for-ns
  {::pc/output [{::m/resource-for-ns [::m/resource-id
                                      ::m/resource-info]}]}
  (fn [{:keys [classpath ast] :as env} input]
    (let [{:keys [ns] :as params} (get-in env [:ast :params])]
      (when (simple-symbol? ns)
        (when-let [{:keys [ns resource-id] :as rc}
                   (cp/find-resource-for-provide classpath ns)]
          {::m/resource-for-ns
           {::m/resource-id resource-id
            ::m/resource-ns ns
            ::m/resource-info (as-resource-info rc)}})))))

(add-resolver `ns-lookup
  {::pc/input #{::m/cljs-ns}
   ::pc/output [::m/cljs-ns-info
                {::m/cljs-ns-defs
                 [::m/cljs-def
                  ::m/cljs-def-meta
                  ::m/cljs-def-doc
                  ::m/cljs-def-info]}
                ::m/cljs-sources]}
  (fn [{:keys [ns-explorer] :as env} {::m/keys [cljs-ns] :as input}]
    (when-let [{:keys [ns-info ns-sources defs] :as ns-data}
               (ex/get-ns-data ns-explorer cljs-ns)]

      {::m/cljs-ns-info ns-info
       ::m/cljs-ns-defs
       (->> (:defs ns-info)
            (map (fn [[def info]]
                   {::m/cljs-def (:name info)
                    ::m/cljs-def-doc (:doc info)
                    ::m/cljs-def-info info
                    ::m/cljs-def-meta (:meta info)}))
            (into []))
       ::m/cljs-sources
       (->> ns-sources
            (map (fn [resource-id]
                   {::m/resource-id resource-id}))
            (into []))}
      )))

(defn as-ident-map [key value]
  {key value})

(defn rc-info-for-provide [classpath provide-sym]
  (let [{:keys [resource-id resource-name ns type] :as rc}
        (cp/find-resource-for-provide classpath provide-sym)]
    (-> {::m/resource-id resource-id
         ::m/resource-name resource-name
         ::m/resource-type type}
        (cond->
          (= :cljs type)
          (assoc ::m/cljs-ns ns)))))

(defn resolve-classpath-query [{:keys [classpath] :as env} input]
  (let [{:keys [type matching] :as params}
        (get-in env [:ast :params])

        filter-fn
        (constantly true)

        type-filter
        (cond
          (nil? type)
          #{:cljs} ;; FIXME: all or cljs-only by default?

          (keyword? type)
          #{type}

          (and (set? type)
               (every? keyword type))
          type

          :else
          (throw (ex-info "invalid params" params)))

        filter-fn
        (fn [x]
          (and (filter-fn x)
               (contains? type-filter (::m/resource-type x))))

        filter-fn
        (if-not matching
          filter-fn
          (fn [x]
            (and (filter-fn x)
                 (re-find (re-pattern matching) (::m/resource-name x)))))]

    {::m/classpath-query
     (->> (cp/get-provided-names classpath)
          (map #(rc-info-for-provide classpath %))
          (filter filter-fn)
          (into []))})
  )

(add-resolver `classpath-query
  {::pc/output [{::m/classpath-query
                 [::m/resource-id
                  ::m/resource-type
                  ::m/cljs-ns]}]}
  resolve-classpath-query)

(def parser
  (p/parser {::p/plugins
             [(p/env-plugin
                {::p/reader [p/map-reader
                             pc/all-readers
                             (p/placeholder-reader ">")]
                 ::pc/resolver-dispatch resolver-fn
                 ::pc/indexes @indexes})
              pp/profile-plugin]}))

