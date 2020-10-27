(ns shadow.cljs.ui.worker.generic
  (:require
    [clojure.string :as str]
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.eql-query :as eql]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.worker.env :as env]))

(sw/reg-event env/app-ref ::m/init!
  (fn [{:keys [db] :as env} _]
    {:graph-api
     {:request
      {:body
       [::m/http-servers
        ;; FIXME: what would be a good place for this definition so that
        ;; the main and worker can share it
        ;; either the full query or just the attributes the components want
        {::m/build-configs
         [::m/build-id
          ::m/build-target
          ::m/build-config-raw
          ::m/build-worker-active
          ]}]}

      :on-success
      {:e ::init-data}}}))

(sw/reg-event env/app-ref ::init-data
  (fn [{:keys [db] :as env} {:keys [result]}]
    (let [{::m/keys [http-servers build-configs]}
          result

          merged
          (-> db
              (assoc ::m/init-complete? true)
              (db/merge-seq ::m/http-server http-servers [::m/http-servers])
              (db/merge-seq ::m/build build-configs [::m/builds]))]
      {:db merged})))

(sw/reg-event env/app-ref ::m/dismiss-error!
  (fn [{:keys [db] :as env} {:keys [ident]}]
    {:db (dissoc db ident)}))

(defmethod eql/attr ::m/errors [env db current query-part params]
  (db/all-idents-of db ::m/error))

(sw/reg-event env/app-ref :ui/route!
  (fn [{:keys [db] :as env} {:keys [token]}]
    ;; FIXME: reitit?
    (let [[main & more :as tokens] (str/split token #"/")
          db (assoc db ::current-route token)]
      (case main
        "inspect"
        {:db (assoc db
               ::m/current-page {:id :inspect}
               ::m/inspect
               {:current 0
                :stack
                [{:type :tap-panel}]})}

        "inspect-latest"
        {:db (assoc db
               ::m/current-page {:id :inspect-latest}
               ::m/inspect {:current 0
                            :stack
                            [{:type :tap-latest-panel}]})}

        "builds"
        {:db (assoc db ::m/current-page {:id :builds})}

        "build"
        (let [[build-id-token] more
              build-id (keyword build-id-token)
              build-ident (db/make-ident ::m/build build-id)]
          {:db (-> db
                   (assoc ::m/current-page {:id :build :ident build-ident})
                   (assoc ::m/current-build build-ident))})

        "dashboard"
        {:db (assoc db ::m/current-page {:id :dashboard})}

        "runtimes"
        {:db (assoc db ::m/current-page {:id :runtimes})}

        "runtime"
        (let [[runtime-id sub-page] more
              runtime-id (js/parseInt runtime-id 10)
              runtime-ident (db/make-ident ::m/runtime runtime-id)]

          (if-not (contains? db runtime-ident)
            ;; FIXME: could try to load it?
            {:ui/redirect! "/runtimes"}

            (case sub-page
              "repl" ;; FIXME: should these be separate page types?
              (let [stream-key [::m/eval-stream runtime-ident]]
                {:db (-> db (assoc ::m/current-page {:id :repl :ident runtime-ident}))
                 :stream-init {stream-key {}}})
              "db-explorer"
              {:db (-> db (assoc ::m/current-page {:id :db-explorer :ident runtime-ident}))}
              (js/console.warn "unknown-runtime-route" token))))

        (js/console.warn "unknown-route" token)
        ))))

