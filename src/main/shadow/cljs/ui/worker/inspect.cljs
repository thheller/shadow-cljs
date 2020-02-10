(ns shadow.cljs.ui.worker.inspect
  (:require
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.eql-query :as eql]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.worker.env :as env]
    [shadow.cljs.ui.worker.tool-ws :as tool-ws]))

(defn without [v item]
  (into [] (remove #{item}) v))

(defn vec-conj [x y]
  (if (nil? x)
    [y]
    (conj x y)))

(defmulti tool-ws (fn [env msg] (:op msg)) :default ::default)

(defmethod tool-ws ::default [env msg]
  (js/console.warn "unhandled websocket msg" msg env)
  {})

(defmethod tool-ws :welcome
  [{:keys [db] :as env} {:keys [tid]}]
  {:db
   (assoc db ::m/tool-id tid)

   :ws-send
   [{:op :request-runtimes}]})

(defmethod tool-ws :runtimes
  [{:keys [db] :as env} {:keys [runtimes] :as msg}]
  {:db
   (db/merge-seq db ::m/runtime runtimes [::m/runtimes])

   :ws-send
   (->> runtimes
        (map (fn [{:keys [rid]}]
               {:op :request-supported-ops :rid rid}))
        (into []))})

(defmethod tool-ws :runtime-connect
  [{:keys [db] :as env} {:keys [runtime-info rid]}]
  (let [runtime {:rid rid
                 :runtime-info runtime-info}]
    {:db
     (db/add db ::m/runtime runtime [::m/runtimes])

     :ws-send
     [{:op :request-supported-ops :rid rid}]}))

(defmethod tool-ws :runtime-disconnect
  [{:keys [db] :as env} {:keys [rid]}]
  (let [runtime-ident (db/make-ident ::m/runtime rid)]
    {:db
     (-> (db/remove db runtime-ident)
         (update ::m/runtimes without runtime-ident))}))

(defmethod tool-ws :supported-ops
  [{:keys [db] :as env} {:keys [ops rid]}]
  (-> {:db
       (db/update-entity db ::m/runtime rid assoc :supported-ops ops)}
      (cond->
        (contains? ops :tap-subscribe)
        (assoc :ws-send [{:op :tap-subscribe :rid rid :summary true :history true}])
        )))

(defmethod tool-ws :tap-subscribed
  [{:keys [db] :as env} {:keys [history rid]}]
  (let [stream-items
        (->> history
             (map (fn [{:keys [oid summary]}]
                    {:type :tap :object-ident (db/make-ident ::m/object oid) :added-at (:added-at summary)}))
             (into []))]

    {:db (reduce
           (fn [db {:keys [oid summary] :as item}]
             (let [object-ident (db/make-ident ::m/object oid)]
               (update db object-ident merge {:db/ident object-ident
                                              :oid oid
                                              :rid rid
                                              :summary summary})))
           db
           history)

     :stream-merge
     {::m/taps stream-items}}))

(defmethod tool-ws :tap [{:keys [db] :as env} {:keys [oid rid]}]
  (let [object-ident (db/make-ident ::m/object oid)]
    {:db
     (db/add db ::m/object {:oid oid :rid rid})

     :stream-add
     [[::m/taps {:type :tap :object-ident object-ident}]]}))

(defmethod tool-ws :obj-summary [{:keys [db] :as env} {:keys [oid summary]}]
  (let [object-ident (db/make-ident ::m/object oid)]
    {:db (assoc-in db [object-ident :summary] summary)}))

(sw/reg-event-fx env/app-ref ::m/tool-ws
  []
  (fn [env {:keys [op] :as msg}]
    ;; (js/console.log ::tool-ws op msg)
    (tool-ws env msg)))

(defmethod eql/attr :obj-preview [env db {:keys [oid rid edn-limit] :as current} query-part params]
  (cond
    edn-limit
    edn-limit

    (or (not oid) (not rid))
    (throw (ex-info "can only request obj-preview on objects" {:current current}))

    ;; FIXME: should maybe track somewhere that we sent this
    ;; FIXME: side effects during read seem like a horrible idea
    ;; but how else do I get lazy-loading behaviour for queries?
    ;; this could return a core.async channel or a promise?
    ;; I'd prefer to handle async stuff on another level though
    ;; leaving this as a hack for now until I can think of something cleaner
    :hack
    (do (tool-ws/call! env
          {:op :obj-request
           :rid rid
           :oid oid
           :request-op :edn-limit
           :limit 150}

          {:obj-result [:edn-limit-preview-loaded]})

        :db/loading)))

(defmethod eql/attr :summary [env db {:keys [oid rid summary] :as current} query-part params]
  (cond
    summary
    summary

    (or (not oid) (not rid))
    (throw (ex-info "can only request obj-preview on objects" {:current current}))

    :hack
    (do (tool-ws/call! env
          {:op :obj-describe
           :rid rid
           :oid oid}
          {:obj-summary [:obj-summary]})

        :db/loading)))

(defmethod eql/attr ::m/object-as-edn [env db {:keys [oid rid edn] :as current} query-part params]
  (cond
    edn
    edn

    (or (not oid) (not rid))
    (throw (ex-info "can only request edn on objects" {:current current}))

    :hack
    (do (tool-ws/call! env
          {:op :obj-request
           :rid rid
           :oid oid
           :request-op :edn}
          {:obj-request-failed [:edn-failed (:db/ident current)]
           :obj-result [:edn-result (:db/ident current)]})
        :db/loading)))

(sw/reg-event-fx env/app-ref :edn-result
  []
  (fn [{:keys [db]} ident {:keys [result]}]
    {:db (assoc-in db [ident :edn] result)}))

(defmethod eql/attr ::m/object-as-pprint [env db {:keys [oid rid pprint] :as current} query-part params]
  (cond
    pprint
    pprint

    (or (not oid) (not rid))
    (throw (ex-info "can only request pprint on objects" {:current current}))

    :hack
    (do (tool-ws/call! env
          {:op :obj-request
           :rid rid
           :oid oid
           :request-op :pprint}
          {:obj-request-failed [:pprint-failed (:db/ident current)]
           :obj-result [:pprint-result (:db/ident current)]})
        :db/loading)))

(sw/reg-event-fx env/app-ref :pprint-result
  []
  (fn [{:keys [db]} ident {:keys [result]}]
    {:db (assoc-in db [ident :pprint] result)}))

(defmethod eql/attr :fragment-vlist
  [env
   db
   {:keys [oid rid summary fragment] :as current}
   _
   {:keys [offset num] :or {offset 0 num 0} :as params}]

  (if-not summary
    (do (throw (ex-info "FIXME: summary not loaded yet for vlist" {:current current}))
        :db/loading)

    (let [{:keys [entries]} summary

          start-idx offset
          last-idx (js/Math.min entries (+ start-idx num))

          slice
          (->> (range start-idx last-idx)
               (reduce
                 (fn [m idx]
                   (let [val (get fragment idx)]
                     (if-not val
                       (reduced nil)
                       (conj! m val))))
                 (transient [])))]

      ;; all requested elements are already present
      (if slice
        {:item-count entries
         :offset offset
         :slice (persistent! slice)}

        ;; missing elements
        ;; FIXME: should be smarter about which elements to fetch
        ;; might already have some
        (do (tool-ws/call! env
              {:op :obj-request
               :rid rid
               :oid oid
               :start start-idx
               :num num
               :request-op :fragment
               :key-limit 100
               :val-limit 100}
              {:obj-result [:fragment-slice-loaded (:db/ident current)]})
            :db/loading)))))

(sw/reg-event-fx env/app-ref :fragment-slice-loaded
  []
  (fn [{:keys [db]} ident {:keys [result]}]
    {:db (update-in db [ident :fragment] merge result)}))

(sw/reg-event-fx env/app-ref :edn-limit-preview-loaded
  []
  (fn [{:keys [db]} {:keys [oid result]}]
    {:db (assoc-in db [(db/make-ident ::m/object oid) :edn-limit] result)}))

(sw/reg-event-fx env/app-ref :obj-summary
  []
  (fn [{:keys [db]} {:keys [oid summary]}]
    (let [ident (db/make-ident ::m/object oid)]
      {:db (update-in db [ident :summary] merge summary)})))

(sw/reg-event-fx env/app-ref ::m/inspect-object!
  []
  (fn [{:keys [db] :as env} ident]
    (let [{:keys [summary oid rid] :as object} (get db ident)]
      (-> {:db (assoc db ::m/inspect {:object ident
                                      :rid rid
                                      :runtime (db/make-ident ::m/runtime rid)
                                      :display-type :browse
                                      :nav-stack []})
           :ws-send []}
          (cond->
            (not summary)
            (-> (assoc-in [:db ident :summary] :db/loading)
                (update :ws-send conj {:op :obj-describe
                                       :oid oid
                                       :rid rid}))
            )))))

(sw/reg-event-fx env/app-ref ::m/inspect-cancel!
  []
  (fn [{:keys [db] :as env}]
    {:db (dissoc db ::m/inspect)}))

(defmethod eql/attr ::m/inspect-active?
  [env db current _ params]
  (contains? db ::m/inspect))

(sw/reg-event-fx env/app-ref ::m/inspect-nav!
  []
  (fn [{:keys [db] :as env} idx]
    (let [{current :object :keys [nav-stack]} (::m/inspect db)
          {:keys [oid rid] :as object} (get db current)

          key (get-in object [:fragment idx :key])]

      (tool-ws/call! env
        {:op :obj-request
         :rid rid
         :oid oid
         :request-op :nav
         :idx idx}

        ;; FIXME: maybe nav should return simple values, instead of ref to simple value
        {:obj-result [:nav-result]
         :obj-result-ref [:nav-result-ref]})

      {:db (-> db
               (update-in [::m/inspect :nav-stack] conj {:idx (count nav-stack)
                                                         :key key
                                                         :ident current})
               (assoc-in [::m/inspect :object] :db/loading))})))

(sw/reg-event-fx env/app-ref ::m/inspect-nav-jump!
  []
  (fn [{:keys [db] :as env} idx]
    (let [{:keys [nav-stack] :as inspect} (::m/inspect db)
          ident (get-in nav-stack [idx :ident])]

      {:db (-> db
               (update ::m/inspect merge {:object ident
                                          :display-type :browse})
               (update-in [::m/inspect :nav-stack] subvec 0 idx))})))

(sw/reg-event-fx env/app-ref ::m/inspect-switch-display!
  []
  (fn [{:keys [db] :as env} display-type]
    {:db (assoc-in db [::m/inspect :display-type] display-type)}))

(sw/reg-event-fx env/app-ref :nav-result-ref
  []
  (fn [{:keys [db] :as env} {:keys [ref-oid rid] :as msg}]
    (let [obj {:oid ref-oid :rid rid}
          obj-ident (db/make-ident ::m/object ref-oid)]

      {:db (-> db
               (db/add ::m/object obj)
               (assoc-in [::m/inspect :object] obj-ident))})))

(defmethod eql/attr ::m/runtimes-sorted
  [env db current query-part params]
  (let [runtimes (::m/runtimes db)]
    (->> runtimes
         (sort-by #(get-in db [% :runtime-info :since]))
         (vec))))

(defmethod eql/attr ::m/cljs-runtimes-sorted
  [env db current query-part params]
  (->> (db/all-of db ::m/runtime)
       (filter #(contains? (:supported-ops %) :eval-cljs))
       (sort-by #(get-in % [:runtime-info :since]))
       (map :db/ident)
       (vec)))

(defmethod eql/attr ::m/clj-runtimes-sorted
  [env db current query-part params]
  (->> (db/all-of db ::m/runtime)
       (filter #(contains? (:supported-ops %) :eval-clj))
       (sort-by #(get-in % [:runtime-info :since]))
       (map :db/ident)
       (vec)))

(sw/reg-event-fx env/app-ref ::m/inspect-code-eval!
  []
  (fn [{:keys [db] :as env} code]
    (let [{::m/keys [inspect] :as data}
          (eql/query env db
            [{::m/inspect
              [{:object [:oid]}
               {:runtime [:rid :supported-ops]}]}])

          {:keys [object runtime]} inspect
          {:keys [oid]} object
          {:keys [rid supported-ops]} runtime

          ;; FIXME: ns and eval mode should come from UI
          [eval-mode ns]
          (cond
            (contains? supported-ops :eval-clj)
            [:eval-clj 'user]
            (contains? supported-ops :eval-cljs)
            [:eval-cljs 'cljs.user])

          wrap
          (str "(let [$ref (shadow.remote.runtime.eval-support/get-ref " (pr-str oid) ")\n"
               "      $o (:obj $ref)\n"
               "      $d (-> $ref :desc :data)]\n"
               "?CODE?\n"
               "\n)")]

      ;; FIXME: fx-ify
      (tool-ws/call! env
        {:op eval-mode
         :rid rid
         :ns ns
         :code code
         :wrap wrap}
        {:eval-result-ref [::inspect-code-result! code rid]})

      {})))

(sw/reg-event-fx env/app-ref ::inspect-code-result!
  []
  (fn [{:keys [db] :as env} code rid {:keys [ref-oid] :as msg}]
    (let [object-ident (db/make-ident ::m/object ref-oid)]
      {:db
       (-> db
           (assoc object-ident {:db/ident object-ident :oid ref-oid :rid rid})
           (assoc-in [::m/inspect :object] object-ident)
           (update-in [::m/inspect :nav-stack] conj
             {:idx (count (get-in db [::m/inspect :nav-stack]))
              :code code
              :ident (get-in db [::m/inspect :object])}))})))

(sw/reg-event-fx env/app-ref ::m/process-eval-input!
  []
  (fn [{:keys [db] :as env} runtime-ident code]
    (let [eval-id (random-uuid)
          eval-ident (db/make-ident ::m/eval eval-id)
          {:keys [rid] :as runtime} (get db runtime-ident)

          ns 'user

          wrap ""
          #_(str "(let [$ref (shadow.remote.runtime.eval-support/get-ref " (pr-str oid) ")\n"
                 "      $o (:obj $ref)\n"
                 "      $d (-> $ref :desc :data)]\n"
                 "?CODE?\n"
                 "\n)")]

      (tool-ws/call! env
        {:op :eval-clj
         :rid rid
         :ns ns
         :code code}
        {:eval-result-ref [::process-eval-result-ref! eval-ident]})

      {:db (assoc db eval-ident {:db/ident eval-ident
                                 :runtime runtime-ident
                                 :eval-id eval-id
                                 :rid rid
                                 :code code
                                 :ns ns
                                 :status :requested})
       :stream-add
       [[[::m/eval-stream runtime-ident] {:ident eval-ident}]]})))

(sw/reg-event-fx env/app-ref ::process-eval-result-ref!
  []
  (fn [{:keys [db] :as env} eval-ident {:keys [ref-oid] :as msg}]
    (let [object-ident (db/make-ident ::m/object ref-oid)
          {:keys [rid]} (get db eval-ident)]
      {:db
       (-> db
           (assoc object-ident {:db/ident object-ident :oid ref-oid :rid rid})
           (update eval-ident merge {:result object-ident
                                     :status :done}))})))

(defmethod eql/attr ::m/databases [env db {:keys [rid] ::m/keys [databases] :as current} query-part params]
  (cond
    databases
    databases

    (not rid)
    (throw (ex-info "can only request ::m/databases for runtime" {:current current}))

    :hack
    (do (tool-ws/call! env
          {:op :db/get-databases
           :rid rid}
          {:db/list-databases [::list-databases (:db/ident current)]})
        :db/loading)))

(sw/reg-event-fx env/app-ref ::list-databases
  []
  (fn [{:keys [db] :as env} runtime-ident {:keys [databases]}]
    (let [{:keys [rid] :as runtime} (get db runtime-ident)]
      {:db (reduce
             (fn [db db-id]
               (let [db-ident (db/make-ident ::m/database [rid db-id])]
                 (-> db
                     (assoc db-ident {:db/ident db-ident
                                      :rid rid
                                      :db-id db-id
                                      ::runtime runtime-ident})
                     (update-in [runtime-ident ::m/databases] conj db-ident)
                     (cond->
                       (= 1 (count databases))
                       (assoc-in [runtime-ident ::m/selected-database] db-ident)
                       ))))
             (assoc-in db [runtime-ident ::m/databases] [])
             databases)})))

(defmethod eql/attr ::m/tables [env db {:keys [rid db-id] ::m/keys [tables] :as current} query-part params]
  (cond
    tables
    tables

    (not db-id)
    (throw (ex-info "can only request ::m/tables for database" {:current current}))

    :hack
    (do (tool-ws/call! env
          {:op :db/get-tables
           :db db-id
           :rid rid}
          {:db/list-tables [::list-tables (:db/ident current)]})
        :db/loading)))

(sw/reg-event-fx env/app-ref ::list-tables
  []
  (fn [{:keys [db] :as env} db-ident {:keys [tables]}]
    {:db (update db db-ident merge {::m/tables tables
                                    ::m/table-query
                                    {:table :db/globals
                                     :row nil}})}))

(defmethod eql/attr ::m/table-rows-vlist
  [env
   db
   {db-ident :db/ident :keys [db-id rid] ::m/keys [table-query table-rows] :as current}
   _
   {:keys [offset num] :or {offset 0 num 0} :as params}]

  (let [{:keys [table]} table-query]

    (cond
      (not table)
      (do (throw (ex-info "FIXME: no table selected" {:current current}))
          :db/loading)

      (not table-rows)
      (do (tool-ws/call! env
            {:op :db/get-rows
             :rid rid
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

(sw/reg-event-fx env/app-ref ::list-rows
  []
  (fn [{:keys [db] :as env} db-ident {:keys [rows] :as msg}]
    {:db (update db db-ident merge {::m/table-rows rows})}))

(sw/reg-event-fx env/app-ref ::m/table-query-update!
  []
  (fn [{:keys [db] :as env} {:keys [db-ident table row] :as msg}]
    (let [{:keys [rid db-id] ::m/keys [table-query]} (get db db-ident)]

      ;; FIXME: make this proper fx!
      (when (not= table (:table table-query))
        (tool-ws/call! env
          {:op :db/get-rows
           :rid rid
           :db db-id
           :table table}
          {:db/list-rows [::list-rows db-ident]}))

      (when (not= row (:row table-query))
        (tool-ws/call! env
          {:op :db/get-entry
           :rid rid
           :db db-id
           :table table
           :row row}
          {:db/entry [::db-table-entry db-ident]}))

      {:db (update-in db [db-ident ::m/table-query] merge msg)}
      )))

(sw/reg-event-fx env/app-ref ::db-table-entry
  []
  (fn [{:keys [db] :as env} db-ident {:keys [row] :as msg}]
    {:db (assoc-in db [db-ident ::m/table-entry] row)}))
