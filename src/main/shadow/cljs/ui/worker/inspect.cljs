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
        (assoc :ws-send [{:op :tap-subscribe :to from}])
        )))

(defn guess-display-type [{:keys [supports] :as summary}]
  (if (contains? supports :fragment)
    :browse

    :edn))

(defmethod relay-ws/handle-msg :tap-subscribed
  [{:keys [db] :as env} {:keys [from]}]
  {})

(defmethod relay-ws/handle-msg :tap [{:keys [db] :as env} {:keys [oid from]}]
  (let [object-ident (db/make-ident ::m/object oid)]
    {:db
     (db/add db ::m/object {:oid oid
                            :runtime-id from
                            :runtime (db/make-ident ::m/runtime from)})

     :stream-add
     [[::m/taps {:type :tap :object-ident object-ident}]]}))

(defmethod relay-ws/handle-msg :obj-summary [{:keys [db] :as env} {:keys [oid summary]}]
  (let [object-ident (db/make-ident ::m/object oid)

        {:keys [display-type] :as obj}
        (get db object-ident)]

    {:db
     (-> db
         (assoc-in [object-ident :summary] (with-added-at-ts summary))
         (cond->
           (nil? display-type)
           (assoc-in [object-ident :display-type] (guess-display-type summary))))}))

(sw/reg-event-fx env/app-ref ::obj-preview-result
  []
  (fn [{:keys [db]} {:keys [call-result]}]
    (let [{:keys [op oid result]} call-result] ;; remote-result
      (assert (= op :obj-result))
      {:db (assoc-in db [(db/make-ident ::m/object oid) :edn-limit] result)})))

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

          {:e ::obj-preview-result})

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

(sw/reg-event-fx env/app-ref ::obj-as-result
  []
  (fn [{:keys [db]} {:keys [ident call-result key]}]
    (let [{:keys [op result]} call-result]
      (assert (= :obj-result op)) ;; FIXME: handle obj-request-failed
      {:db (assoc-in db [ident key] result)})))

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

          {:e ::obj-as-result
           :ident (:db/ident current)
           :key :edn})
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
          {:e ::obj-as-result
           :ident (:db/ident current)
           :key :str})
        :db/loading)))

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
          {:e ::obj-as-result
           :ident (:db/ident current)
           :key :pprint})
        :db/loading)))

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
              {:e ::fragment-slice-loaded
               :ident (:db/ident current)})
            :db/loading)))))

(sw/reg-event-fx env/app-ref ::fragment-slice-loaded
  []
  (fn [{:keys [db]} {:keys [ident call-result]}]
    (let [{:keys [op result]} call-result]
      (assert (= :obj-result op)) ;; FIXME: handle failures
      {:db (update-in db [ident :fragment] merge result)})))

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

              {:e ::lazy-seq-slice-loaded
               :ident (:db/ident current)})

            :db/loading)))))

(sw/reg-event-fx env/app-ref ::lazy-seq-slice-loaded
  []
  (fn [{:keys [db]} {:keys [ident call-result]}]
    (let [{:keys [op realized fragment more?]} call-result]
      (assert (= :obj-result op)) ;; FIXME: handle failures
      {:db (-> db
               (assoc-in [ident :realized] realized)
               (assoc-in [ident :more?] more?)
               (update-in [ident :fragment] merge fragment))})))



(sw/reg-event-fx env/app-ref ::m/inspect-object!
  []
  (fn [{:keys [db] :as env} {:keys [ident]}]
    (let [{:keys [summary oid runtime-id] :as object} (get db ident)]
      (let [stack
            (-> (get-in db [::m/inspect :stack])
                (subvec 0 1)
                (conj {:type :object-panel
                       :ident ident}))]

        (-> {:db (-> db
                     (assoc-in [::m/inspect :stack] stack)
                     (assoc-in [::m/inspect :current] 1))
             :ws-send []}
            (cond->
              (not summary)
              (-> (assoc-in [:db ident :summary] :db/loading)
                  (update :ws-send conj {:op :obj-describe
                                         :to runtime-id
                                         :oid oid}))
              ))))))

(defmethod eql/attr ::m/inspect-object
  [env db current query-part params]
  (let [{:keys [nav-stack]} (::m/inspect db)
        {:keys [ident] :as last} (last nav-stack)]
    ident))

(sw/reg-event-fx env/app-ref ::m/inspect-nav!
  []
  (fn [{:keys [db] :as env} {:keys [ident idx panel-idx]}]
    (let [{:keys [oid runtime-id] :as object} (get db ident)]

      (relay-ws/call! env
        {:op :obj-request
         :to runtime-id
         :oid oid
         :request-op :nav
         :idx idx
         :summary true}

        {:e ::inspect-nav-result
         :ident ident
         :panel-idx panel-idx})

      {})))

(sw/reg-event-fx env/app-ref ::inspect-nav-result
  []
  (fn [{:keys [db] :as env} {:keys [panel-idx call-result] :as tx}]

    (assert (= :obj-result-ref (:op call-result))) ;; FIXME: handle failures

    (let [{:keys [ref-oid from summary]}
          call-result

          obj
          {:oid ref-oid
           :runtime-id from
           :runtime (db/make-ident ::m/runtime from)
           :summary summary
           :display-type (guess-display-type summary)}

          obj-ident
          (db/make-ident ::m/object ref-oid)

          {:keys [stack]}
          (::m/inspect db)

          stack
          (-> (subvec stack 0 (inc panel-idx))
              (conj {:type :object-panel
                     :ident obj-ident}))]

      {:db (-> db
               (db/add ::m/object obj)
               (assoc-in [::m/inspect :stack] stack)
               (assoc-in [::m/inspect :current] (inc panel-idx)))})))

(sw/reg-event-fx env/app-ref ::m/inspect-set-current!
  []
  (fn [{:keys [db] :as env} {:keys [idx]}]
    {:db (assoc-in db [::m/inspect :current] idx)}))

(sw/reg-event-fx env/app-ref ::m/inspect-nav-jump!
  []
  (fn [{:keys [db] :as env} {:keys [idx]}]
    (let [idx (inc idx)]

      {:db (-> db
               (update-in [::m/inspect :nav-stack] subvec 0 idx))})))

(sw/reg-event-fx env/app-ref ::m/inspect-switch-display!
  []
  (fn [{:keys [db] :as env} {:keys [ident display-type]}]
    {:db (assoc-in db [ident :display-type] display-type)}))



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
  (fn [{:keys [db] :as env} {:keys [code ident panel-idx]}]
    (let [data
          (eql/query env db
            [{ident
              [:oid
               {:runtime
                [:runtime-id
                 :supported-ops]}]}])

          {:keys [oid runtime]} (get data ident)
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
        {:e ::inspect-eval-result!
         :code code
         :panel-idx panel-idx})
      {})))

(sw/reg-event-fx env/app-ref ::inspect-eval-result!
  []
  (fn [{:keys [db] :as env} {:keys [code panel-idx call-result]}]
    (case (:op call-result)
      :eval-result-ref
      (let [{:keys [ref-oid from warnings]} call-result]
        (when (seq warnings)
          (doseq [w warnings]
            (js/console.warn "FIXME: warning not yet displayed in UI" w)))

        (let [object-ident
              (db/make-ident ::m/object ref-oid)

              stack
              (-> (get-in db [::m/inspect :stack])
                  (subvec 0 (inc panel-idx))
                  (conj {:type :object-panel
                         :ident object-ident}))]
          {:db
           (-> db
               (assoc object-ident
                      {:db/ident object-ident
                       :oid ref-oid
                       :runtime-id from
                       :runtime (db/make-ident ::m/runtime from)})
               (assoc-in [::m/inspect :current] (inc panel-idx))
               (assoc-in [::m/inspect :stack] stack))}))

      :eval-compile-error
      (let [{:keys [from ex-oid ex-client-id]} call-result
            object-ident (db/make-ident ::m/object ex-oid)]
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
                :ident (get-in db [::m/inspect :object])}))})

      :eval-runtime-error
      (let [{:keys [from ex-oid]} call-result
            object-ident (db/make-ident ::m/object ex-oid)]
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
                :ident (get-in db [::m/inspect :object])}))})
      )))
