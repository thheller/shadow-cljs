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
            (sort-by :build-id)
            (map adapt-build-config)
            (into []))})))

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

;; FIXME: move deftx to a shared namespace

(add-mutation 'shadow.cljs.ui.transactions/build-watch-start
  {::pc/input #{:build-id}
   ::pc/output [::m/build-id
                ::m/build-worker-active]}
  (fn [{:keys [supervisor] :as env} {:keys [build-id] :as input}]
    (let [config (config/get-build build-id)
          worker (super/start-worker supervisor config)]
      (worker/start-autobuild worker))

    {::m/build-id build-id
     ::m/build-worker-active true}))

(add-mutation 'shadow.cljs.ui.transactions/build-watch-stop
  {::pc/input #{:build-id}
   ::pc/output [::m/build-id
                ::m/build-worker-active]}
  (fn [{:keys [supervisor] :as env} {:keys [build-id] :as input}]
    (super/stop-worker supervisor build-id)
    {::m/build-id build-id
     ::m/build-worker-active false}
    ))

(add-mutation 'shadow.cljs.ui.transactions/build-watch-compile
  {::pc/input #{:build-id}
   ::pc/output [::m/build-id]}
  (fn [{:keys [supervisor] :as env} {:keys [build-id] :as input}]
    (let [worker (super/get-worker supervisor build-id)]
      (worker/compile worker))
    ;; FIXME: can this return something useful?
    {::m/build-id build-id}))

(add-mutation 'shadow.cljs.ui.transactions/build-compile
  {::pc/input #{:build-id}
   ::pc/output [::m/build-id]}
  (fn [{:keys [system-bus] :as env} {:keys [build-id] :as input}]

    (future
      (let [build-config (config/get-build build-id)
            pub-msg
            (fn [msg]
              (sys-bus/publish! system-bus ::m/worker-broadcast msg)
              (sys-bus/publish! system-bus [::m/worker-output build-id] msg))]
        (try
          ;; not at all useful to send this message but want to match worker message flow for now
          (pub-msg {:type :build-configure
                    :build-id build-id
                    :build-config build-config})

          (pub-msg {:type :build-start
                    :build-id build-id})

          (let [build-state
                (-> (util/new-build build-config :dev {})
                    (build-api/with-logger
                      (reify
                        build-log/BuildLog
                        (log* [this build-state log-event]
                          (pub-msg {:type :build-log
                                    :build-id build-id
                                    :event log-event}))))
                    (build/configure :dev build-config)
                    (build/compile)
                    (build/flush))]

            (pub-msg {:type :build-complete
                      :build-id build-id
                      :info (::build/build-info build-state)}))

          (catch Exception e
            (pub-msg {:type :build-failure
                      :build-id build-id
                      :report (binding [warnings/*color* false]
                                (errors/error-format e))
                      })))))

    {::m/build-id build-id}
    ))

(add-mutation 'shadow.cljs.ui.transactions/build-release
  {::pc/input #{:build-id}
   ::pc/output [::m/build-id]}
  (fn [env {:keys [build-id] :as input}]
    (log/warn ::build-compile {:input input})
    {::m/build-id build-id}))
