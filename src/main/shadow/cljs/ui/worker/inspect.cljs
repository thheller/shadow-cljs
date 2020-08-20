(ns shadow.cljs.ui.worker.inspect
  (:require
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.eql-query :as eql]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.worker.env :as env]
    [shadow.cljs.ui.worker.relay-ws :as relay-ws]
    [clojure.string :as str])
  (:import [goog.i18n DateTimeFormat]))

(defn without [v item]
  (into [] (remove #{item}) v))

(defn vec-conj [x y]
  (if (nil? x)
    [y]
    (conj x y)))

(def ts-format
  (DateTimeFormat. "HH:mm:ss.SSS"))

(defn with-added-at-ts [{:keys [added-at] :as summary}]
  (assoc summary :added-at-ts (.format ts-format (js/Date. added-at))))

(defmethod relay-ws/handle-msg :clients
  [{:keys [db] :as env} {:keys [clients] :as msg}]
  {:db
   (let [runtimes
         (->> clients
              (map (fn [{:keys [client-id client-info]}]
                     {:runtime-id client-id
                      :runtime-info client-info}))
              (vec))]
     (db/merge-seq db ::m/runtime runtimes [::m/runtimes]))

   :ws-send
   [{:op :request-supported-ops
     :to (->> clients
              (map :client-id)
              (into #{}))}]})

(defmethod relay-ws/handle-msg :notify
  [{:keys [db] :as env}
   {:keys [event-op client-id client-info]}]
  (case event-op
    :client-connect
    (let [runtime {:runtime-id client-id
                   :runtime-info client-info}]
      {:db
       (db/add db ::m/runtime runtime [::m/runtimes])

       :ws-send
       [{:op :request-supported-ops :to client-id}]})

    :client-disconnect
    (let [runtime-ident (db/make-ident ::m/runtime client-id)]
      {:db
       (-> (db/remove db runtime-ident)
           (update ::m/runtimes without runtime-ident))})))

(defmethod relay-ws/handle-msg :supported-ops
  [{:keys [db] :as env} {:keys [ops from]}]
  (-> {:db
       (db/update-entity db ::m/runtime from assoc :supported-ops ops)}
      (cond->
        (contains? ops :tap-subscribe)
        (assoc :ws-send [{:op :tap-subscribe :to from :summary true :history true}])
        )))

(defmethod relay-ws/handle-msg :tap-subscribed
  [{:keys [db] :as env} {:keys [history from]}]
  (let [stream-items
        (->> history
             (map (fn [{:keys [oid summary]}]
                    {:type :tap
                     :object-ident (db/make-ident ::m/object oid)
                     :added-at (:added-at summary)}))
             (into []))]

    {:db (reduce
           (fn [db {:keys [oid summary] :as item}]
             (let [object-ident (db/make-ident ::m/object oid)]
               (update db object-ident merge {:db/ident object-ident
                                              :oid oid
                                              :runtime-id from
                                              :runtime (db/make-ident ::m/runtime from)
                                              :summary (with-added-at-ts summary)})))
           db
           history)

     :stream-merge
     {::m/taps stream-items}}))

(defmethod relay-ws/handle-msg :tap [{:keys [db] :as env} {:keys [oid from]}]
  (let [object-ident (db/make-ident ::m/object oid)]
    {:db
     (db/add db ::m/object {:oid oid
                            :runtime-id from
                            :runtime (db/make-ident ::m/runtime from)})

     :stream-add
     [[::m/taps {:type :tap :object-ident object-ident}]]}))



(defmethod relay-ws/handle-msg :obj-summary [{:keys [db] :as env} {:keys [oid summary]}]
  (let [object-ident (db/make-ident ::m/object oid)]
    {:db (assoc-in db [object-ident :summary] (with-added-at-ts summary))}))



(defmethod eql/attr :obj-preview [env db {:keys [oid runtime-id edn-limit] :as current} query-part params]
  (cond
    edn-limit
    edn-limit

    (or (not oid) (not runtime-id))
    (throw (ex-info "can only request obj-preview on objects" {:current current}))

    ;; FIXME: should maybe track somewhere that we sent this
    ;; FIXME: side effects during read seem like a horrible idea
    ;; but how else do I get lazy-loading behaviour for queries?
    ;; this could return a core.async channel or a promise?
    ;; I'd prefer to handle async stuff on another level though
    ;; leaving this as a hack for now until I can think of something cleaner
    :hack
    (do (relay-ws/call! env
          {:op :obj-request
           :to runtime-id
           :oid oid
           :request-op :edn-limit
           :limit 150}

          {:obj-result [:edn-limit-preview-loaded]})

        :db/loading)))

(defmethod eql/attr :summary [env db {:keys [oid runtime-id summary] :as current} query-part params]
  (cond
    summary
    summary

    (or (not oid) (not runtime-id))
    (throw (ex-info "can only request obj-preview on objects" {:current current}))

    :hack
    (do (relay-ws/cast! env
          {:op :obj-describe
           :to runtime-id
           :oid oid})

        :db/loading)))

(defmethod eql/attr ::m/object-as-edn [env db {:keys [oid runtime-id edn] :as current} query-part params]
  (cond
    edn
    edn

    (or (not oid) (not runtime-id))
    (throw (ex-info "can only request edn on objects" {:current current}))

    :hack
    (do (relay-ws/call! env
          {:op :obj-request
           :to runtime-id
           :oid oid
           :request-op :edn}
          {:obj-request-failed [:edn-failed (:db/ident current)]
           :obj-result [:edn-result (:db/ident current)]})
        :db/loading)))

(defmethod eql/attr ::m/object-as-str [env db {:keys [oid runtime-id str] :as current} query-part params]
  (cond
    str
    str

    (or (not oid) (not runtime-id))
    (throw (ex-info "can only request edn on objects" {:current current}))

    :hack
    (do (relay-ws/call! env
          {:op :obj-request
           :to runtime-id
           :oid oid
           :request-op :str}
          {:obj-request-failed [:edn-failed (:db/ident current)]
           :obj-result [:str-result (:db/ident current)]})
        :db/loading)))

(sw/reg-event-fx env/app-ref :edn-result
  []
  (fn [{:keys [db]} ident {:keys [result]}]
    {:db (assoc-in db [ident :edn] result)}))

(sw/reg-event-fx env/app-ref :str-result
  []
  (fn [{:keys [db]} ident {:keys [result]}]
    {:db (assoc-in db [ident :str] result)}))

(defmethod eql/attr ::m/object-as-pprint [env db {:keys [oid runtime-id pprint] :as current} query-part params]
  (cond
    pprint
    pprint

    (or (not oid) (not runtime-id))
    (throw (ex-info "can only request pprint on objects" {:current current}))

    :hack
    (do (relay-ws/call! env
          {:op :obj-request
           :to runtime-id
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
   {:keys [oid runtime-id summary fragment] :as current}
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
        (do (relay-ws/call! env
              {:op :obj-request
               :to runtime-id
               :oid oid
               :start start-idx
               :num num
               :request-op :fragment
               :key-limit 160
               :val-limit 160}
              {:obj-result [:fragment-slice-loaded (:db/ident current)]})
            :db/loading)))))

(sw/reg-event-fx env/app-ref :fragment-slice-loaded
  []
  (fn [{:keys [db]} ident {:keys [result]}]
    {:db (update-in db [ident :fragment] merge result)}))

(defmethod eql/attr :lazy-seq-vlist
  [env
   db
   {:keys [oid runtime-id summary realized more? fragment] :as current}
   _
   {:keys [offset num] :or {offset 0 num 0} :as params}]

  (js/console.log "lazy-seq-vlist" current)
  (if-not summary
    (do (throw (ex-info "FIXME: summary not loaded yet for vlist" {:current current}))
        :db/loading)

    (let [start-idx offset
          last-idx (js/Math.min
                     (if-not (false? more?)
                       (or realized num)
                       realized)
                     (+ start-idx num))

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
        {:item-count realized
         :offset offset
         :more? more?
         :slice (persistent! slice)}

        (do (relay-ws/call! env
              {:op :obj-request
               :to runtime-id
               :oid oid
               :start start-idx
               :num num
               :request-op :chunk
               :val-limit 100}
              {:obj-result [:lazy-seq-slice-loaded (:db/ident current)]})
            :db/loading)))))

(sw/reg-event-fx env/app-ref :lazy-seq-slice-loaded
  []
  (fn [{:keys [db]} ident {:keys [result]}]
    (let [{:keys [realized fragment more?]} result]
      {:db (-> db
               (assoc-in [ident :realized] realized)
               (assoc-in [ident :more?] more?)
               (update-in [ident :fragment] merge fragment))})))

(sw/reg-event-fx env/app-ref :edn-limit-preview-loaded
  []
  (fn [{:keys [db]} {:keys [oid result]}]
    {:db (assoc-in db [(db/make-ident ::m/object oid) :edn-limit] result)}))

(sw/reg-event-fx env/app-ref ::m/inspect-object!
  []
  (fn [{:keys [db] :as env} ident]
    (let [{:keys [summary oid runtime-id] :as object} (get db ident)]
      (-> {:db (assoc db ::m/inspect {:object ident
                                      :runtime-id runtime-id
                                      :runtime (db/make-ident ::m/runtime runtime-id)
                                      :nav-stack [{:idx 0 :ident ident}]})
           :ws-send []}
          (cond->
            (not summary)
            (-> (assoc-in [:db ident :summary] :db/loading)
                (update :ws-send conj {:op :obj-describe
                                       :to runtime-id
                                       :oid oid}))
            )))))

(sw/reg-event-fx env/app-ref ::m/inspect-cancel!
  []
  (fn [{:keys [db] :as env}]
    {:db (dissoc db ::m/inspect)}))

(defmethod eql/attr ::m/inspect-active?
  [env db current _ params]
  (contains? db ::m/inspect))

(defmethod eql/attr ::m/inspect-object
  [env db current query-part params]
  (let [{:keys [nav-stack]} (::m/inspect db)
        {:keys [ident] :as last} (last nav-stack)]
    ident))

(sw/reg-event-fx env/app-ref ::m/inspect-nav!
  []
  (fn [{:keys [db] :as env} current idx]
    (let [{:keys [oid runtime-id] :as object} (get db current)]

      (relay-ws/call! env
        {:op :obj-request
         :to runtime-id
         :oid oid
         :request-op :nav
         :idx idx
         :summary true}

        ;; FIXME: maybe nav should return simple values, instead of ref to simple value
        {:obj-result [:nav-result]
         :obj-result-ref [:nav-result-ref]})

      {})))

(sw/reg-event-fx env/app-ref ::m/inspect-nav-jump!
  []
  (fn [{:keys [db] :as env} idx]
    (let [idx (inc idx)]

      {:db (-> db
               (update-in [::m/inspect :nav-stack] subvec 0 idx))})))

(sw/reg-event-fx env/app-ref ::m/inspect-switch-display!
  []
  (fn [{:keys [db] :as env} ident display-type]
    {:db (assoc-in db [ident :display-type] display-type)}))

(sw/reg-event-fx env/app-ref :nav-result-ref
  []
  (fn [{:keys [db] :as env} {:keys [ref-oid from summary] :as msg}]
    (let [obj {:oid ref-oid
               :runtime-id from
               :runtime (db/make-ident ::m/runtime from)
               :summary summary}
          obj-ident (db/make-ident ::m/object ref-oid)

          {:keys [nav-stack]} (::m/inspect db)]

      {:db (-> db
               (db/add ::m/object obj)
               (update-in [::m/inspect :nav-stack] conj {:idx (count nav-stack) :ident obj-ident}))})))

(defmethod eql/attr ::m/runtimes-sorted
  [env db current query-part params]
  (let [runtimes (::m/runtimes db)]
    (->> runtimes
         (sort-by #(get-in db [% :runtime-info :since]))
         (vec))))

(defmethod eql/attr ::m/cljs-runtimes-sorted
  [env db current query-part params]
  (->> (db/all-of db ::m/runtime)
       (filter #(= :cljs (get-in % [:runtime-info :lang])))
       (sort-by #(get-in % [:runtime-info :since]))
       (map :db/ident)
       (vec)))

(defmethod eql/attr ::m/clj-runtimes-sorted
  [env db current query-part params]
  (->> (db/all-of db ::m/runtime)
       (filter #(= :clj (get-in % [:runtime-info :lang])))
       (sort-by #(get-in % [:runtime-info :since]))
       (map :db/ident)
       (vec)))

(sw/reg-event-fx env/app-ref ::m/inspect-code-eval!
  []
  (fn [{:keys [db] :as env} code]
    (let [{::m/keys [inspect-object] :as data}
          (eql/query env db
            [{::m/inspect-object
              [:oid
               {:runtime
                [:runtime-id
                 :supported-ops]}]}])

          {:keys [oid runtime]} inspect-object
          {:keys [runtime-id supported-ops]} runtime

          ;; FIXME: ns and eval mode should come from UI
          [eval-mode ns]
          (cond
            (contains? supported-ops :clj-eval)
            [:clj-eval 'user]
            (contains? supported-ops :cljs-eval)
            [:cljs-eval 'cljs.user])

          input
          (-> {:ns ns
               :code code}
              (cond->
                (or (str/includes? code "$o")
                    (str/includes? code "$d"))
                (assoc :wrap
                       (str "(let [$ref (shadow.remote.runtime.eval-support/get-ref " (pr-str oid) ")\n"
                            "      $o (:obj $ref)\n"
                            "      $d (-> $ref :desc :data)]\n"
                            "?CODE?\n"
                            "\n)"))))]

      ;; FIXME: fx-ify
      (relay-ws/call! env
        {:op eval-mode
         :to runtime-id
         :input input}
        {:eval-result-ref [::inspect-eval-result! code]
         :eval-compile-error [::inspect-eval-compile-error! code]
         :eval-runtime-error [::inspect-eval-runtime-error! code]})
      {})))

(sw/reg-event-fx env/app-ref ::inspect-eval-result!
  []
  (fn [{:keys [db] :as env} code {:keys [ref-oid from warnings] :as msg}]
    (when (seq warnings)
      (doseq [w warnings]
        (js/console.warn "FIXME: warning not yet displayed in UI" w)))
    (let [object-ident (db/make-ident ::m/object ref-oid)]
      {:db
       (-> db
           (assoc object-ident
                  {:db/ident object-ident
                   :oid ref-oid
                   :runtime-id from
                   :runtime (db/make-ident ::m/runtime from)})
           (update-in [::m/inspect :nav-stack] conj
             {:idx (count (get-in db [::m/inspect :nav-stack]))
              :code code
              :ident object-ident}))})))

(sw/reg-event-fx env/app-ref ::inspect-eval-compile-error!
  []
  (fn [{:keys [db] :as env} code {:keys [from ex-oid ex-client-id] :as msg}]
    (let [object-ident (db/make-ident ::m/object ex-oid)]
      {:db
       (-> db
           (assoc object-ident
                  {:db/ident object-ident
                   :oid ex-oid
                   :runtime-id (or ex-client-id from)
                   :runtime (db/make-ident ::m/runtime (or ex-client-id from))
                   :is-error true})
           (assoc-in [::m/inspect :object] object-ident)
           (update-in [::m/inspect :nav-stack] conj
             {:idx (count (get-in db [::m/inspect :nav-stack]))
              :code code
              :ident (get-in db [::m/inspect :object])}))})))

(sw/reg-event-fx env/app-ref ::inspect-eval-runtime-error!
  []
  (fn [{:keys [db] :as env} code {:keys [from ex-oid] :as msg}]
    (let [object-ident (db/make-ident ::m/object ex-oid)]
      {:db
       (-> db
           (assoc object-ident
                  {:db/ident object-ident
                   :oid ex-oid
                   :runtime-id from
                   :runtime (db/make-ident ::m/runtime from)
                   :is-error true})
           (assoc-in [::m/inspect :object] object-ident)
           (update-in [::m/inspect :nav-stack] conj
             {:idx (count (get-in db [::m/inspect :nav-stack]))
              :code code
              :ident (get-in db [::m/inspect :object])}))})))

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

(sw/reg-event-fx env/app-ref ::list-databases
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

(sw/reg-event-fx env/app-ref ::list-rows
  []
  (fn [{:keys [db] :as env} db-ident {:keys [rows] :as msg}]
    {:db (update db db-ident merge {::m/table-rows rows})}))

(sw/reg-event-fx env/app-ref ::m/table-query-update!
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

(sw/reg-event-fx env/app-ref ::db-table-entry
  []
  (fn [{:keys [db] :as env} db-ident {:keys [row] :as msg}]
    {:db (assoc-in db [db-ident ::m/table-entry] row)}))
