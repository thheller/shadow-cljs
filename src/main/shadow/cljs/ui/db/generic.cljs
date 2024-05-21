(ns shadow.cljs.ui.db.generic
  (:require
    [shadow.grove :as sg]
    [shadow.grove.events :as ev]
    [shadow.cljs :as-alias m]
    [shadow.grove.kv :as kv]))

(defn init!
  {::ev/handle ::m/init!}
  [env _]
  (-> env
      (assoc-in [::m/ui ::m/preferred-display-type] (keyword (or (js/localStorage.getItem "preferred-display-type") "browse")))
      (sg/queue-fx :http-api
        {:request "/ui-init-data"
         :on-success {:e ::init-data}})))

(defn init-data
  {::ev/handle ::init-data}
  [env {:keys [result]}]
  (let [{::m/keys [http-servers build-configs]} result]
    (-> env
        (assoc-in [::m/ui ::m/init-complete?] true)
        (kv/merge-seq ::m/http-server http-servers [::m/ui ::m/http-servers])
        (kv/merge-seq ::m/build build-configs [::m/ui ::m/builds]))))

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

      "runtime"
      (let [[runtime-id sub-page] more
            runtime-id (js/parseInt runtime-id 10)]

        (if-not (contains? (::m/runtime env) runtime-id)
          ;; FIXME: could try to load it?
          (ev/queue-fx env :ui/redirect! {:token "/runtimes"})

          (case sub-page
            "eval"
            (-> env
                (assoc-in [::m/ui ::m/inspect]
                  {:current 0
                   :stack [{:type :eval-panel
                            :runtime-id runtime-id}]})
                ;; FIXME: make this a custom page at some point
                (assoc-in [::m/ui ::m/current-page] {:id :repl :runtime-id runtime-id}))

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