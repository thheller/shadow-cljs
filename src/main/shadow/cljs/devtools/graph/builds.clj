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
    [shadow.build.log :as build-log]
    [clojure.core.async :as async :refer (>!!)]))

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

(add-resolver `http-servers
  {::pc/output [{::m/http-servers [::m/http-server-id
                                   ::m/http-url
                                   ::m/https-url]}]}
  (fn [{:keys [dev-http] :as env} _]
    (let [servers
          (->> (:servers @dev-http)
               (sort-by :build-id)
               (map (fn [{:keys [http-url https-url build-id]}]
                      {::m/http-server-id build-id
                       ::m/build {::m/build-id build-id}
                       ::m/http-url http-url
                       ::m/https-url https-url}))
               (into []))]

      {::m/http-servers servers})))

(add-resolver `build-http-servers
  {::pc/input #{::m/build-id}
   ::pc/output [{::m/build-http-server [::m/http-server-id
                                        ::m/http-url
                                        ::m/https-url]}]}
  (fn [{:keys [dev-http] :as env} {::m/keys [build-id] :as params}]
    (let [server
          (->> (:servers @dev-http)
               (filter #(= build-id (:build-id %)))
               (map (fn [{:keys [http-url https-url build-id]}]
                      {::m/http-server-id build-id
                       ::m/build-id {::m/build-id build-id}
                       ::m/http-url http-url
                       ::m/https-url https-url}))
               (first))]

      {::m/build-http-server server})))

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

(defn do-build [{:keys [system-bus] :as env} build-id mode]
  (future
    (let [build-config
          (config/get-build build-id)

          status-ref
          (atom {:status :pending
                 :mode mode
                 :log []})

          log-chan
          (async/chan 10)

          loop
          (worker/build-status-loop system-bus build-id status-ref log-chan)

          pub-msg
          (fn [msg]
            (>!! log-chan msg)
            ;; FIXME: this is not worker output but adding an extra channel seems like overkill
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
              (-> (util/new-build build-config mode {})
                  (build-api/with-logger
                    (util/async-logger log-chan))
                  (build/configure mode build-config)
                  (build/compile)
                  (cond->
                    (= :release mode)
                    (build/optimize))
                  (build/flush))]

          (pub-msg {:type :build-complete
                    :build-id build-id
                    :info (::build/build-info build-state)}))

        (catch Exception e
          (pub-msg {:type :build-failure
                    :build-id build-id
                    :report (binding [warnings/*color* false]
                              (errors/error-format e))
                    }))
        (finally
          (async/close! log-chan))))))

(add-mutation 'shadow.cljs.ui.transactions/build-compile
  {::pc/input #{:build-id}
   ::pc/output [::m/build-id]}
  (fn [env {:keys [build-id] :as input}]
    (do-build env build-id :dev)
    {::m/build-id build-id}
    ))

(add-mutation 'shadow.cljs.ui.transactions/build-release
  {::pc/input #{:build-id}
   ::pc/output [::m/build-id]}
  (fn [env {:keys [build-id] :as input}]
    (do-build env build-id :release)
    {::m/build-id build-id}))
