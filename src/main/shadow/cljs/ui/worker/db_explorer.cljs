(ns shadow.cljs.ui.worker.db-explorer)

(defmethod eql/attr ::m/databases [env db {:keys [runtime-id] ::m/keys [databases] :as current} query-part params]
  (cond
    databases
    databases

    (not runtime-id)
    (throw (ex-info "can only request ::m/databases for runtime" {:current current}))

    :hack
    (do (relay-ws/call! env
          {:op :db/get-databases
           :to runtime-id}
          {:db/list-databases [::list-databases (:db/ident current)]})
        :db/loading)))

(sw/reg-event env/app-ref ::list-databases
  []
  (fn [{:keys [db] :as env} runtime-ident {:keys [databases]}]
    (let [{:keys [runtime-id] :as runtime} (get db runtime-ident)]
      {:db (reduce
             (fn [db db-id]
               (let [db-ident (db/make-ident ::m/database [runtime-id db-id])]
                 (-> db
                     (assoc db-ident {:db/ident db-ident
                                      :runtime-id runtime-id
                                      :db-id db-id
                                      ::m/runtime runtime-ident})
                     (update-in [runtime-ident ::m/databases] conj db-ident)
                     (cond->
                       (= 1 (count databases))
                       (assoc-in [runtime-ident ::m/selected-database] db-ident)
                       ))))
             (assoc-in db [runtime-ident ::m/databases] [])
             databases)})))

(defmethod eql/attr ::m/tables [env db {:keys [runtime-id db-id] ::m/keys [tables] :as current} query-part params]
  (cond
    tables
    tables

    (not db-id)
    (throw (ex-info "can only request ::m/tables for database" {:current current}))

    :hack
    (do (relay-ws/call! env
          {:op :db/get-tables
           :to runtime-id
           :db db-id}
          {:db/list-tables [::list-tables (:db/ident current)]})
        :db/loading)))

(sw/reg-event env/app-ref ::list-tables
  []
  (fn [{:keys [db] :as env} db-ident {:keys [tables]}]
    {:db (update db db-ident merge {::m/tables tables
                                    ::m/table-query
                                    {:table :db/globals
                                     :row nil}})}))

(defmethod eql/attr ::m/table-rows-vlist
  [env
   db
   {db-ident :db/ident :keys [db-id runtime-id] ::m/keys [table-query table-rows] :as current}
   _
   {:keys [offset num] :or {offset 0 num 0} :as params}]

  (let [{:keys [table]} table-query]

    (cond
      (not table)
      (do (throw (ex-info "FIXME: no table selected" {:current current}))
          :db/loading)

      (not table-rows)
      (do (relay-ws/call! env
            {:op :db/get-rows
             :to runtime-id
             :db db-id
             :table table}
            {:db/list-rows [::list-rows db-ident]})
          :db/loading)

      :else
      (let [start-idx offset
            last-idx (js/Math.min (count table-rows) (+ start-idx num))

            slice
            (->> (range start-idx last-idx)
                 (reduce
                   (fn [m idx]
                     (let [val (get table-rows idx)]
                       (if-not val
                         (reduced nil)
                         (conj! m val))))
                   (transient [])))]

        ;; all requested elements are already present
        (if-not slice
          (throw (ex-info "missing table rows?" {}))
          {:item-count (count table-rows)
           :offset offset
           :slice (persistent! slice)})))))

(sw/reg-event env/app-ref ::list-rows
  []
  (fn [{:keys [db] :as env} db-ident {:keys [rows] :as msg}]
    {:db (update db db-ident merge {::m/table-rows rows})}))

(sw/reg-event env/app-ref ::m/table-query-update!
  []
  (fn [{:keys [db] :as env} {:keys [db-ident table row] :as msg}]
    (let [{:keys [runtime-id db-id] ::m/keys [table-query]} (get db db-ident)]

      ;; FIXME: make this proper fx!
      (when (not= table (:table table-query))
        (relay-ws/call! env
          {:op :db/get-rows
           :to runtime-id
           :db db-id
           :table table}
          {:db/list-rows [::list-rows db-ident]}))

      (when (not= row (:row table-query))
        (relay-ws/call! env
          {:op :db/get-entry
           :to runtime-id
           :db db-id
           :table table
           :row row}
          {:db/entry [::db-table-entry db-ident]}))

      {:db (update-in db [db-ident ::m/table-query] merge msg)}
      )))

(sw/reg-event env/app-ref ::db-table-entry
  []
  (fn [{:keys [db] :as env} db-ident {:keys [row] :as msg}]
    {:db (assoc-in db [db-ident ::m/table-entry] row)}))
