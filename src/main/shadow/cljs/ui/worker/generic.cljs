(ns shadow.cljs.ui.worker.generic
  (:require
    [clojure.string :as str]
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.worker.env :as env]))

(sw/reg-event-fx env/app-ref ::m/init!
  []
  (fn [{:keys [db] :as env} token]
    {:graph-api
     {:request {:body [::m/http-servers
                       ;; FIXME: what would be a good place for this definition so that
                       ;; the main and worker can share it
                       ;; either the full query or just the attributes the components want
                       {::m/build-configs
                        [::m/build-id
                         ::m/build-target
                         ::m/build-config-raw
                         ::m/build-worker-active
                         ]}]}
      :on-success [::init-data]}}))

(sw/reg-event-fx env/app-ref ::init-data
  []
  (fn [{:keys [db] :as env} {::m/keys [http-servers build-configs] :as data}]
    (let [merged
          (-> db
              (assoc ::m/init-complete? true)
              (db/merge-seq ::m/http-server http-servers [::m/http-servers])
              (db/merge-seq ::m/build build-configs [::m/builds]))]
      {:db merged})))

(sw/reg-event-fx env/app-ref :ui/route!
  []
  (fn [{:keys [db] :as env} token]
    (let [[main & more :as tokens] (str/split token #"/")
          db (assoc db ::current-route token)]
      (case main
        "inspect"
        {:db (assoc db ::m/current-page {:id :inspect})}

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
              ;; FIXME: assuming sub-page /repl for now
              runtime-id (js/parseInt runtime-id 10)
              runtime-ident (db/make-ident ::m/runtime runtime-id)]
          {:db (-> db
                   (assoc ::m/current-page {:id :repl :ident runtime-ident}))})

        (js/console.warn "unknown-route" token)
        ))))

