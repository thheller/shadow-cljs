(ns shadow.cljs.devtools.server.remote-ext
  "shadow.remote extension for clj-runtime adding shadow-cljs specific ops"
  (:require
    [shadow.remote.runtime.api :as p]
    [shadow.cljs.model :as m]
    [clojure.core.async :as async :refer (go <!)]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.build.log :as build-log]
    [shadow.cljs.devtools.server.util :as server-util]
    [shadow.build.api :as build-api]
    [shadow.build :as build]
    [shadow.cljs.devtools.errors :as errors]
    [shadow.build.warnings :as warnings]))

(defn subscribe
  [{:keys [state-ref runtime system-bus] :as svc}
   {:keys [from] ::m/keys [topic]}]
  (let [sub-chan
        (-> (async/sliding-buffer 100)
            (async/chan))]

    (log/debug ::ws-subscribe {:topic topic})

    (sys-bus/sub system-bus topic sub-chan)

    (go (loop []
          (when-some [msg (<! sub-chan)]
            ;; msg already contains ::m/topic due to sys-bus
            (p/relay-msg runtime (assoc msg :op ::m/sub-msg :to from))
            (recur)))

        ;; subs only end when server is shutting down
        ;; no need to tell the client, it will notice
        #_ (p/relay-msg runtime {:op ::m/sub-close ::m/topic topic}))

    (swap! state-ref update :subs conj {:client-id from
                                        :topic topic
                                        :sub-chan sub-chan})

    (let [sub-runtimes
          (->> (:subs @state-ref)
               (map :client-id)
               (set))]

      ;; want to be notified when subbed runtimes disconnect
      (p/relay-msg runtime
        {:op :request-notify
         :notify-op ::runtime-update
         :query [:contained-in :client-id sub-runtimes]}))))

(defn build-watch-start
  [{:keys [supervisor] :as svc} {::m/keys [build-id]}]
  (let [config (config/get-build build-id)
        ;; FIXME: needs access to cli opts used to start server?
        worker (super/start-worker supervisor config {})]
    (worker/start-autobuild worker)))

(defn build-watch-stop
  [{:keys [supervisor] :as svc} {::m/keys [build-id]}]
  (super/stop-worker supervisor build-id))

(defn build-watch-compile
  [{:keys [supervisor] :as svc} {::m/keys [build-id]}]
  (let [worker (super/get-worker supervisor build-id)]
    (worker/compile worker)))

(defn do-build [{:keys [system-bus] :as svc} build-id mode cli-opts]
  (future
    (let [build-config
          (config/get-build build-id)

          build-logger
          (reify
            build-log/BuildLog
            (log*
              [_ state event]
              (sys-bus/publish system-bus ::m/build-log
                {:type :build-log
                 :build-id build-id
                 :event event})))

          pub-msg
          (fn [msg]
            ;; FIXME: this is not worker output but adding an extra channel seems like overkill
            (sys-bus/publish system-bus ::m/worker-broadcast msg)
            (sys-bus/publish system-bus [::m/worker-output build-id] msg))]
      (try
        ;; not at all useful to send this message but want to match worker message flow for now
        (pub-msg {:type :build-configure
                  :build-id build-id
                  :build-config build-config})

        (pub-msg {:type :build-start
                  :build-id build-id})

        (let [build-state
              (-> (server-util/new-build build-config mode {})
                  (build-api/with-logger build-logger)
                  (build/configure mode build-config cli-opts)
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
        ))))

(defn build-compile
  [svc {::m/keys [build-id]}]
  (do-build svc build-id :dev {}))

(defn build-release
  [svc {::m/keys [build-id]}]
  (do-build svc build-id :release {}))

(defn build-release-debug
  [svc {::m/keys [build-id]}]
  (do-build svc build-id :release
    {:config-merge
     [{:compiler-options
       {:pseudo-names true
        :pretty-print true}}]}))

(defn load-ui-options
  [{:keys [runtime] :as svc} {:keys [from] :as msg}]
  (let [config
        (config/load-cljs-edn!)

        ui-options
        (merge
          (:ui-options config)
          (get-in config [:user-config :ui-options]))]

    (p/relay-msg runtime
      {:op ::m/ui-options
       :to from
       :ui-options ui-options})))

(defn runtime-update
  [{:keys [state-ref] :as svc}
   {:keys [event-op client-id]}]
  (when (= :client-disconnect event-op)
    (doseq [sub (:subs @state-ref)
            :when (= client-id (:client-id sub))]
      (async/close! (:sub-chan sub)))

    (swap! state-ref update :subs
      (fn [current]
        (->> current
             (remove #(= client-id (:client-id %)))
             (vec))))))

(defn start [runtime supervisor system-bus]

  (let [state-ref
        (atom {:subs []})

        svc
        {:runtime runtime
         :supervisor supervisor
         :system-bus system-bus
         :state-ref state-ref}]

    (p/add-extension runtime
      ::ext
      {:ops
       {::m/subscribe #(subscribe svc %)
        ::m/build-watch-start! #(build-watch-start svc %)
        ::m/build-watch-compile! #(build-watch-compile svc %)
        ::m/build-watch-stop! #(build-watch-stop svc %)
        ::m/build-compile! #(build-compile svc %)
        ::m/build-release! #(build-release svc %)
        ::m/build-release-debug! #(build-release-debug svc %)
        ::m/load-ui-options #(load-ui-options svc %)
        ::runtime-update #(runtime-update svc %)}})

    svc))

(defn stop [{:keys [runtime]}]
  (p/del-extension runtime ::ext))
