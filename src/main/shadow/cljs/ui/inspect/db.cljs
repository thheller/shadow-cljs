(ns shadow.cljs.ui.inspect.db
  (:require
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.env :as env]
    [shadow.cljs.ui.tool-ws :as tool-ws]))

(defn without [v item]
  (into [] (remove #{item}) v))

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
  [{:keys [db] :as env} msg]
  {:db
   (db/merge-seq db ::m/runtime (:runtimes msg) [::m/runtimes])

   :ws-send
   (->> (:runtimes msg)
        (map (fn [{:keys [rid]}]
               {:op :request-supported-ops :rid rid}))
        (into []))})

(defmethod tool-ws :runtime-connect
  [{:keys [db] :as env} {:keys [runtime-info rid]}]
  (let [runtime {:rid rid :runtime-info runtime-info}]
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

(defmethod db/query-calc :obj-preview [env db {:keys [oid rid edn-limit] :as current} query-part params]
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

(defmethod db/query-calc :summary [env db {:keys [oid rid summary] :as current} query-part params]
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

(defmethod db/query-calc ::m/object-as-edn [env db {:keys [oid rid edn] :as current} query-part params]
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

(defmethod db/query-calc ::m/object-as-pprint [env db {:keys [oid rid pprint] :as current} query-part params]
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

(defmethod db/query-calc :fragment-vlist
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
                       (assoc! m idx val))))
                 (transient {})))]

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

(defmethod db/query-calc ::m/inspect-active?
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

(defmethod db/query-calc ::m/runtimes-sorted
  [env db current query-part params]
  (let [runtimes (::m/runtimes db)]
    (->> runtimes
         (sort-by #(get-in db [% :runtime-info :since]))
         (vec))))