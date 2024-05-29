(ns shadow.cljs.ui.db.generic
  (:require
    [shadow.cljs.ui.db.relay-ws :as relay-ws]
    [shadow.grove :as sg]
    [shadow.grove.events :as ev]
    [shadow.cljs :as-alias m]
    [shadow.grove.kv :as kv]))

(defn init!
  {::ev/handle ::m/init!}
  [env _]
  (-> env
      (assoc-in [::m/ui ::m/preferred-display-type] (keyword (or (js/localStorage.getItem "preferred-display-type") "browse")))))



(defn dismiss-error!
  {::ev/handle ::m/dismiss-error!}
  [env {:keys [error-id]}]
  (update env ::m/error dissoc error-id))

(defn ui-route!
  {::ev/handle :ui/route!}
  [{:keys [db] :as env} {:keys [tokens] :as msg}]

  (let [[main & more] tokens]

    (case main
      "inspect"
      (update env ::m/ui assoc
        ::m/current-page {:id :inspect}
        ::m/inspect
        {:current 0
         :stack
         [{:type :tap-panel}]})

      "inspect-latest"
      (update env ::m/ui assoc
        ::m/current-page {:id :inspect-latest}
        ::m/inspect {:current 0
                     :stack
                     [{:type :tap-latest-panel}]})

      "builds"
      (update env ::m/ui assoc
        ::m/current-page {:id :builds})

      "build"
      (let [[build-id-token sub-page] more
            build-id (keyword build-id-token)
            build-tab
            (case sub-page
              "runtimes" :runtimes
              "config" :config
              :status)]
        (update env ::m/ui assoc
          ::m/current-page
          {:id :build
           :build-id build-id
           :tab build-tab}
          ::m/current-build build-id))

      "dashboard"
      (assoc-in env [::m/ui ::m/current-page] {:id :dashboard})

      "runtimes"
      (assoc-in env [::m/ui ::m/current-page] {:id :runtimes})

      "repl"
      (-> env
          (assoc-in [::m/ui ::m/current-page] {:id :repl})
          (assoc-in [::m/ui ::m/inspect]
            {:current 0
             :stack [{:type :repl-panel
                      :stream-id :default}]}))

      "runtime"
      (let [[runtime-id sub-page] more
            runtime-id (js/parseInt runtime-id 10)]

        (if-not (contains? (::m/runtime env) runtime-id)
          ;; FIXME: could try to load it?
          (ev/queue-fx env :ui/redirect! {:token "/runtimes"})

          (case sub-page
            "explore"
            (-> env
                (update ::m/ui assoc
                  ::m/current-page {:id :explore-runtime}
                  ::m/inspect {:current 0
                               :stack
                               [{:type :explore-runtime-panel
                                 :runtime-id runtime-id}]})
                (update-in [::m/runtime runtime-id] dissoc ::m/explore-ns ::m/explore-var ::m/explore-var-object))

            (js/console.warn "unknown-runtime-route" tokens))))

      (do (js/console.warn "unknown-route" msg)
          (ev/queue-fx env :ui/redirect! {:token "/dashboard"}))
      )))

(defn switch-preferred-display-type!
  {::ev/handle ::m/switch-preferred-display-type!}
  [env {:keys [display-type]}]
  ;; FIXME: fx this
  (js/localStorage.setItem "preferred-display-type" (name display-type))
  (assoc-in env [::m/ui ::m/preferred-display-type] display-type))

(defn close-settings!
  {::ev/handle ::m/close-settings!}
  [env _]
  (assoc-in env [::m/ui ::m/show-settings] false))

(defn open-settings!
  {::ev/handle ::m/open-settings!}
  [env _]
  (assoc-in env [::m/ui ::m/show-settings] true))

(defn db-sync
  {::ev/handle ::m/db-sync}
  [env {::m/keys [builds repl-streams repl-history http-servers]}]
  (-> env
      (assoc-in [::m/ui ::m/init-complete?] true)
      (kv/merge-seq ::m/http-server http-servers)
      (kv/merge-seq ::m/build builds)
      (kv/merge-seq ::m/repl-stream repl-streams)
      (kv/merge-seq ::m/repl-history repl-history)))

(defn db-update
  {::ev/handle ::m/db-update}
  [env {:keys [changes]}]
  (reduce
    (fn [env [op & more :as change]]
      (case op
        (:entity-add :entity-update)
        (let [[table id key val] more]
          (assoc-in env [table id key] val))

        :entity-remove
        (let [[table id key] more]
          (update-in env [table id] dissoc key))

        :table-add
        (let [[table id val] more]
          (assoc-in env [table id] val))

        :table-remove
        (let [[table id] more]
          (update env table dissoc id))

        (do (js/console.log "unknown op" change)
            env)))
    env
    changes))

(defn repl-switch!
  {::ev/handle ::m/repl-select-runtime!}
  [env {:keys [stream-id value]}]
  (-> env
      (cond->
        value
        (sg/queue-fx :relay-send
          [{:op ::m/repl-stream-switch!
            :to 1
            :stream-id stream-id
            :target value
            :target-ns 'shadow.user
            ;; FIXME: if there ever is support for multiple CLJ targets this will break
            :target-op (if (= value 1) :clj-eval :cljs-eval)}]))))

