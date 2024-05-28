(ns shadow.cljs.devtools.server.remote-ext
  "shadow.remote extension for clj-runtime adding shadow-cljs specific ops"
  (:require
    [clojure.core.async :as async :refer (go <!)]
    [clojure.tools.reader.reader-types :as readers]
    [shadow.cljs.devtools.server.sync-db :as sync-db]
    [shadow.cljs.repl :as repl]
    [shadow.remote.runtime.api :as p]
    [shadow.cljs :as-alias m]
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
    [shadow.build.warnings :as warnings]
    [shadow.remote.runtime.shared :as shared]))

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
        #_(p/relay-msg runtime {:op ::m/sub-close ::m/topic topic}))

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

(defn notify
  [{:keys [state-ref sync-db] :as svc}
   {:keys [event-op client-id client-info] :as msg}]
  (case event-op
    :client-connect
    (swap! state-ref update :clients assoc client-id client-info)

    :client-disconnect
    (let [client-info (get-in @state-ref [:clients client-id])]
      ;; FIXME: should probably check if actual UI?
      (remove-watch sync-db [::ui client-id])
      (swap! state-ref update :clients dissoc client-id))))

(defn repl-stream-start [svc {:keys [stream-id from] :as msg}]
  ;; FIXME: when supporting actual multiple streams this is supposed to initialize it
  )


(defn db-sync-init
  [{:keys [state-ref sync-db runtime] :as svc}
   {:keys [from] :as msg}]

  (let [db @sync-db]
    (shared/reply runtime msg
      {:op ::m/db-sync
       ::m/builds (vec (vals (::m/build db)))
       ::m/http-servers (vec (vals (::m/http-server db)))
       ::m/repl-streams (vec (vals (::m/repl-stream db)))
       ::m/repl-history (vec (vals (::m/repl-history db)))}))

  ;; FIXME: this will get somewhat expensive
  ;; should use the same strategy as shadow.grove.kv to track writes
  ;; but this is good enough for now
  (add-watch sync-db [::ui from]
    (fn [_ _ old-db new-db]
      (let [changes (sync-db/db-diff old-db new-db)]
        (when (seq changes)
          (p/relay-msg runtime
            {:op ::m/db-update
             :to from
             :changes changes}))))))

(defn vec-conj [x y]
  (if (nil? x)
    [y]
    (conj x y)))

(defn eval-next
  [{:keys [state-ref sync-db runtime] :as svc} stream-id]

  ;; trying to prevent race conditions in a kinda ugly way
  ;; should really use proper queues instead of a single atom
  ;; but this is fine since it is unlikely that things are going to queue up too much
  (when-not (get-in @state-ref [:stream-busy stream-id])
    (swap! state-ref assoc-in [:stream-busy stream-id] true)

    (let [[oval nval]
          (swap-vals! state-ref
            (fn [state]
              (let [q (get-in state [:stream-queue stream-id])]
                (if (seq q) ;; pop unhappy if empty
                  (update-in state [:stream-queue stream-id] subvec 1)
                  state
                  ))))

          entry-id (first (get-in oval [:stream-queue stream-id]))]

      (if-not entry-id
        (swap! state-ref update :stream-busy dissoc stream-id)

        ;; start the actual eval
        (let [{:keys [code] :as entry}
              (get-in @sync-db [::m/repl-history entry-id])

              {:keys [target target-op target-ns]}
              (get-in @sync-db [::m/repl-stream stream-id])

              result-fn
              (fn [{:keys [eval-ns] :as result}]
                (sync-db/update! sync-db update-in [::m/repl-history entry-id] merge {:result (dissoc result :call-id) :ts-result (System/currentTimeMillis)})

                (when eval-ns
                  (sync-db/update! sync-db assoc-in [::m/repl-stream stream-id :target-ns] eval-ns))

                (swap! state-ref update :stream-busy dissoc stream-id)
                (eval-next svc stream-id))]

          (log/debug ::repl-eval {:stream-id stream-id :target target :target-op target-op :target-ns target-ns :code code})

          (sync-db/update! sync-db update-in [::m/repl-history entry-id] merge
            {:ts-eval (System/currentTimeMillis)
             :target target
             :target-op target-op
             :target-ns target-ns})

          (shared/call runtime
            {:to target
             :op target-op
             :input {:code code
                     :ns target-ns}}

            ;; let the UI deal with further stuff for now
            ;; but should at least send something back to stream
            {:eval-result-ref result-fn
             :eval-compile-error result-fn
             :eval-compile-warnings result-fn
             :eval-runtime-error result-fn}))))))

(defonce id-seq-ref (atom 0))

(defn repl-stream-input
  [{:keys [state-ref sync-db] :as svc}
   {:keys [stream-id from code] :as msg}]

  (when (seq code)
    (let [rdr (readers/source-logging-push-back-reader code)]
      (loop []
        ;; the submitted code string may contain multiple forms, so this acts as a tokenizer
        ;; saves putting the burden on the client to only submit a single form at a time
        (let [{:keys [eof? source]} (repl/dummy-read-one rdr {:read-cond :preserve})]
          (when-not eof?
            (let [id (swap! id-seq-ref inc)

                  new-entry
                  {:id id
                   :ts-in (System/currentTimeMillis)
                   :stream-id stream-id
                   :from from
                   :code source}]

              (sync-db/update! sync-db update ::m/repl-history assoc id new-entry)
              (swap! state-ref update-in [:stream-queue stream-id] vec-conj id))
            (recur))))

      ;; eval should happen in order sequentially for now
      (eval-next svc stream-id))))

(defn repl-stream-switch
  [{:keys [sync-db] :as svc}
   {:keys [stream-id target target-ns target-op] :as msg}]
  (sync-db/update! sync-db update-in [::m/repl-stream stream-id] merge {:target target :target-ns target-ns :target-op target-op}))

(defn start [runtime sync-db supervisor system-bus]
  (let [state-ref
        (atom
          {:subs []
           :clients {}
           :stream-queue {}
           :stream-busy {}})

        svc
        {:runtime runtime
         :sync-db sync-db
         :supervisor supervisor
         :system-bus system-bus
         :state-ref state-ref}]

    (p/add-extension runtime
      ::ext
      {:on-welcome
       (fn []
         (p/relay-msg runtime
           {:op :request-notify
            :notify-op ::notify}))
       :ops
       {::m/subscribe #(subscribe svc %)
        ::m/build-watch-start! #(build-watch-start svc %)
        ::m/build-watch-compile! #(build-watch-compile svc %)
        ::m/build-watch-stop! #(build-watch-stop svc %)
        ::m/build-compile! #(build-compile svc %)
        ::m/build-release! #(build-release svc %)
        ::m/build-release-debug! #(build-release-debug svc %)
        ::m/load-ui-options #(load-ui-options svc %)
        ::notify #(notify svc %)
        ::runtime-update #(runtime-update svc %)
        ::m/db-sync-init! #(db-sync-init svc %)
        ::m/repl-stream-start! #(repl-stream-start svc %)
        ::m/repl-stream-input! #(repl-stream-input svc %)
        ::m/repl-stream-switch! #(repl-stream-switch svc %)
        }})

    svc))

(defn stop [{:keys [runtime]}]
  (p/del-extension runtime ::ext))
