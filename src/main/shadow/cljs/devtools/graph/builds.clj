(ns shadow.cljs.devtools.graph.builds
  (:require
    [com.wsscode.pathom.connect :as pc]
    [shadow.build :as build]
    [shadow.cljs.model :as m]
    [shadow.jvm-log :as log]
    [shadow.build.api :as build-api]
    [shadow.build.warnings :as warnings]
    [shadow.cljs.devtools.graph.env :as genv :refer (add-resolver add-mutation)]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server.util :as util]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs.devtools.errors :as errors]
    [shadow.build.log :as build-log]))

(def config-attrs
  [::m/build-id
   ::m/build-target
   ::m/build-config-raw])

(defn adapt-build-config [{:keys [build-id target] :as config}]
  {::m/build-id build-id
   ::m/build-target target
   ::m/build-config-raw config})

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
           (build/configure mode build-config {})
           (build/resolve)
           :build-sources)})))

(add-resolver `build-configs
  {::pc/output [{::m/build-configs config-attrs}]}
  (fn [env _]
    (let [{:keys [builds] :as config}
          (config/load-cljs-edn)]

      {::m/build-configs
       (->> (vals builds)
            (sort-by :build-id)
            (remove #(-> % meta :generated))
            (map adapt-build-config)
            (into []))})))

(add-resolver `http-servers
  {::pc/output [{::m/http-servers [::m/http-server-id
                                   ::m/http-url
                                   ::m/http-config
                                   ::m/https-url]}]}
  (fn [{:keys [dev-http] :as env} _]
    (let [servers
          (->> (:servers @dev-http)
               (map-indexed
                 (fn [idx {:keys [http-url https-url config]}]
                      {::m/http-server-id idx
                       ::m/http-url http-url
                       ::m/http-config config
                       ::m/https-url https-url}))
               (into []))]

      {::m/http-servers servers})))

(add-resolver `build-worker
  {::pc/input #{::m/build-id}
   ::pc/output [::m/build-worker-active
                ::m/build-status]}
  (fn [{:keys [supervisor] :as env} {::m/keys [build-id] :as input}]
    (let [{:keys [status-ref] :as worker} (super/get-worker supervisor build-id)]
      {::m/build-worker-active (some? worker)
       ::m/build-status (if worker
                          @status-ref
                          {:status :inactive})})))

(add-resolver `repl-runtimes
  {::pc/output [{::m/repl-runtimes
                 [::m/runtime-id
                  ::m/runtime-active
                  ::m/runtime-info]}]}
  (fn [{:keys [repl-system] :as env} input]
    {::m/repl-runtimes
     (->> (:state-ref repl-system)
          (deref)
          (:runtimes)
          (vals)
          (sort-by #(get-in % [:runtime-info :build-id]))
          (map (fn [{:keys [runtime-id runtime-info] :as x}]
                 {::m/runtime-id runtime-id
                  ::m/runtime-active true
                  ::m/runtime-info runtime-info}))
          (into []))}))


;; resolves dependencies of a given entry namespace
;; based on the given build config
(add-resolver `build-entry-deps
  {::pc/input #{::m/build-id}
   ::pc/output [{::m/entry-deps [::m/resource-id]}]}
  (fn [env {::m/keys [build-id] :as input}]

    (let [{:keys [entry mode] :or {mode :dev}}
          (get-in env [:ast :params])

          config
          (config/get-build build-id)

          [resolved state]
          (-> (util/new-build config mode {})
              (build-api/resolve-entries [entry]))]

      {::m/entry-deps
       (->> resolved
            (map (fn [resource-id]
                   {::m/resource-id resource-id}))
            (into []))}
      )))