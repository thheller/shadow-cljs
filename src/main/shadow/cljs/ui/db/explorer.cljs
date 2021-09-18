(ns shadow.cljs.ui.db.explorer
  (:require
    [shadow.experiments.grove.eql-query :as eql]
    [shadow.experiments.grove.events :as ev]
    [shadow.cljs.model :as m]
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.ui.db.relay-ws :as relay-ws]))

(defn apply-namespace-filter [tx runtime-ident]
  (assoc-in tx [:db runtime-ident ::m/runtime-namespaces-filtered]
    (get-in tx [:db runtime-ident ::m/runtime-namespaces])))

(defn runtimes-reply
  {::ev/handle ::runtimes-reply}
  [tx {:keys [runtime-ident call-result] :as msg}]
  (let [{:keys [namespaces]} call-result] ;; remote-result

    (-> tx
        (update :db db/merge-seq ::m/runtime-ns
          (->> namespaces
               (map (fn [ns]
                      {:ns ns
                       ::m/runtime runtime-ident})))
          [runtime-ident ::m/runtime-namespaces])
        (apply-namespace-filter runtime-ident))))

(defn vars-reply
  {::ev/handle ::vars-reply}
  [{:keys [db] :as tx} {:keys [ns-ident call-result] :as msg}]
  (let [{:keys [vars]} call-result
        {:keys [ns] :as runtime-ns} (get db ns-ident)
        runtime (::m/runtime runtime-ns)]
    (-> tx
        (update :db db/merge-seq ::m/runtime-var
          (->> vars
               (map (fn [var]
                      {:var (symbol (name ns) (name var))
                       ::m/runtime runtime
                       ::m/runtime-ns ns-ident}
                      )))
          [ns-ident ::m/runtime-vars]
          ))))

(defn get-target-id-for-runtime [{:keys [runtime-id runtime-info supported-ops] :as runtime}]
  (cond
    (contains? supported-ops :explore/namespaces)
    runtime-id

    ;; can't ask cljs directly, need to ask worker
    (= :cljs (:lang runtime-info))
    (:worker-id runtime-info)

    :else
    (throw (ex-info "can't query for runtime namespaces" {:runtime runtime}))))

(defmethod eql/attr ::m/runtime-namespaces
  [env
   db
   {:keys [runtime-id]
    ::m/keys [runtime-namespaces]
    :as current}
   query-part
   params]

  (cond
    runtime-namespaces
    runtime-namespaces

    (not runtime-id)
    (throw (ex-info "can only request ::m/runtime-namespaces for runtime" {:current current}))

    :hack
    (do (relay-ws/call! env
          {:op :explore/namespaces
           :to (get-target-id-for-runtime current)}
          {:e ::runtimes-reply
           :runtime-ident (:db/ident current)})
        :db/loading)))

(defn runtime-select-namespace!
  {::ev/handle ::m/runtime-select-namespace!}
  [{:keys [db] :as tx} {:keys [ident ns] :as msg}]
  (let [{:keys [ns] ::m/keys [runtime] :as rt-ns}
        (get db ident)

        runtime
        (get db runtime)]

    (-> tx
        (assoc-in [:db (:db/ident runtime) ::m/explore-ns] ident)
        (cond->
          (not (:vars rt-ns))
          (ev/queue-fx :relay-send
            [{:op :explore/namespace-vars
              :to (get-target-id-for-runtime runtime)
              :ns ns

              ::relay-ws/result
              {:e ::vars-reply
               :ns-ident ident}}]
            )))))

(defn describe-var-result!
  {::ev/handle ::describe-var-result!}
  [{:keys [db] :as tx} {:keys [ident call-result] :as msg}]
  (assoc-in tx [:db ident :description] (:description call-result)))

(defn deref-var-result!
  {::ev/handle ::deref-var-result!}
  [{:keys [db] :as tx} {:keys [runtime-ident call-result] :as msg}]

  (let [{:keys [op]} call-result]
    (case op
      :eval-result-ref
      (let [{:keys [ref-oid from]} call-result
            object-ident (db/make-ident ::m/object ref-oid)]
        (-> tx
            (assoc-in [:db object-ident]
              {:db/ident object-ident
               :oid ref-oid
               :runtime-id from
               :runtime (db/make-ident ::m/runtime from)})
            (assoc-in [:db runtime-ident ::m/explore-var-object] object-ident)))

      :eval-runtime-error
      (let [{:keys [ex-oid from]} call-result
            object-ident (db/make-ident ::m/object ex-oid)]
        (-> tx
            (assoc-in [:db object-ident]
              {:db/ident object-ident
               :oid ex-oid
               :runtime-id from
               :runtime (db/make-ident ::m/runtime from)
               :is-error true})
            (assoc-in [:db runtime-ident ::m/explore-var-object] object-ident)))

      (throw (ex-info "not handled" msg)))))

(defn runtime-select-var!
  {::ev/handle ::m/runtime-select-var!}
  [{:keys [db] :as tx} {:keys [ident] :as msg}]
  (let [rt-var
        (get db ident)

        rt
        (get db (::m/runtime rt-var))]

    (-> tx
        (assoc-in [:db (:db/ident rt) ::m/explore-var] ident)
        (ev/queue-fx :relay-send
          ;; always fetching these even if we might have them
          ;; might have changed in the meantime with no clean way to tell
          [{:op :explore/describe-var
            :to (get-target-id-for-runtime rt)
            :var (:var rt-var)
            ::relay-ws/result
            {:e ::describe-var-result!
             :ident ident}}

           (when-some [op (case (get-in rt [:runtime-info :lang])
                            :clj :clj-eval
                            :cljs :cljs-eval
                            nil)]
             {:op op
              :to (:runtime-id rt) ;; always goes directly to runtime
              :input {:ns (symbol (namespace (:var rt-var)))
                      :code (str (:var rt-var))}
              ::relay-ws/result
              {:e ::deref-var-result!
               :runtime-ident (:db/ident rt)}})
           ]))))


(defn runtime-deselect-var!
  {::ev/handle ::m/runtime-deselect-var!}
  [tx {:keys [runtime-ident] :as msg}]
  (update-in tx [:db runtime-ident] dissoc ::m/explore-var ::m/explore-var-object))