(ns shadow.cljs.ui.db.generic
  (:require
    [shadow.grove :as sg]
    [shadow.grove.events :as ev]
    [shadow.grove.db :as db]
    [shadow.grove.eql-query :as eql]
    [shadow.cljs.model :as m]
    ))

(defn init!
  {::ev/handle ::m/init!}
  [env _]
  (sg/queue-fx env :http-api
    {:request "/ui-init-data"
     :on-success {:e ::init-data}}))

(defn init-data
  {::ev/handle ::init-data}
  [env {:keys [result]}]
  (let [{::m/keys [http-servers build-configs]}
        result]
    (update env :db
      (fn [db]
        (-> db
            (assoc ::m/init-complete? true)
            (db/merge-seq ::m/http-server http-servers [::m/http-servers])
            (db/merge-seq ::m/build build-configs [::m/builds]))))))

(defn dismiss-error!
  {::ev/handle ::m/dismiss-error!}
  [{:keys [db] :as env} {:keys [ident]}]
  (update env :db dissoc ident))

(defmethod eql/attr ::m/errors [env db current query-part params]
  (db/all-idents-of db ::m/error))

(defn ui-route!
  {::ev/handle :ui/route!}
  [{:keys [db] :as env} {:keys [tokens] :as msg}]

  (let [[main & more] tokens]

    (case main
      "inspect"
      (update env :db assoc
        ::m/current-page {:id :inspect}
        ::m/inspect
        {:current 0
         :stack
         [{:type :tap-panel}]})

      "inspect-latest"
      (update env :db assoc
        ::m/current-page {:id :inspect-latest}
        ::m/inspect {:current 0
                     :stack
                     [{:type :tap-latest-panel}]})

      "builds"
      (update env :db assoc
        ::m/current-page {:id :builds})

      "build"
      (let [[build-id-token sub-page] more
            build-id (keyword build-id-token)
            build-ident (db/make-ident ::m/build build-id)
            build-page-id
            (case sub-page
              "runtimes"
              :build+runtimes
              :build+status)]
        (update env :db
          (fn [db]
            (-> db
                (assoc ::m/current-page
                       {:id build-page-id
                        :ident build-ident})
                (assoc ::m/current-build build-ident)))))

      "dashboard"
      (assoc-in env [:db ::m/current-page] {:id :dashboard})

      "runtimes"
      (assoc-in env [:db ::m/current-page] {:id :runtimes})

      "runtime"
      (let [[runtime-id sub-page] more
            runtime-id (js/parseInt runtime-id 10)
            runtime-ident (db/make-ident ::m/runtime runtime-id)]

        (if-not (contains? db runtime-ident)
          ;; FIXME: could try to load it?
          (ev/queue-fx env :ui/redirect! {:token "/runtimes"})

          (case sub-page
            "repl" ;; FIXME: should these be separate page types?
            (assoc-in env [:db ::m/current-page] {:id :repl :ident runtime-ident})

            "db-explorer"
            (assoc-in env [:db ::m/current-page] {:id :db-explorer :ident runtime-ident})

            "explore"
            (update env :db
              (fn [db]
                (-> db
                    (assoc ::m/current-page {:id :explore-runtime}
                           ::m/inspect {:current 0
                                        :stack
                                        [{:type :explore-runtime-panel
                                          :runtime runtime-ident}]})
                    (update runtime-ident dissoc ::m/explore-ns ::m/explore-var ::m/explore-var-object))))

            (js/console.warn "unknown-runtime-route" tokens))))

      (js/console.warn "unknown-route" tokens)
      )))

