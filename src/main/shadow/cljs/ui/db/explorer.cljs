(ns shadow.cljs.ui.db.explorer
  (:require
    [shadow.grove :as sg]
    [shadow.grove.events :as ev]
    [shadow.grove.kv :as kv]
    [shadow.cljs :as-alias m]
    [shadow.cljs.ui.db.relay-ws :as relay-ws]))

(defn runtimes-reply
  {::ev/handle ::runtimes-reply}
  [tx {:keys [runtime-id call-result] :as msg}]
  (let [{:keys [namespaces]} call-result] ;; remote-result
    (assoc-in tx [::m/runtime runtime-id :runtime-namespaces] namespaces)))

(defn vars-reply
  {::ev/handle ::vars-reply}
  [tx {:keys [runtime-id ns call-result] :as msg}]
  (let [{:keys [vars]} call-result]
    (assoc-in tx [::m/runtime runtime-id :runtime-vars ns] vars)))

(defn get-target-id-for-runtime [{:keys [runtime-id runtime-info supported-ops] :as runtime}]
  (cond
    (contains? supported-ops :explore/namespaces)
    runtime-id

    ;; can't ask cljs directly, need to ask worker
    (= :cljs (:lang runtime-info))
    (:worker-id runtime-info)

    :else
    (throw (ex-info "can't query for runtime namespaces" {:runtime runtime}))))

(defn maybe-load-runtime-namespaces [env runtime]
  (when-not (::m/runtime-namespaces runtime)
    (relay-ws/call! (::sg/runtime-ref env)
      {:op :explore/namespaces
       :to (get-target-id-for-runtime runtime)}
      {:e ::runtimes-reply
       :runtime-id (:runtime-id runtime)})))

(defn runtime-select-namespace!
  {::ev/handle ::m/runtime-select-namespace!}
  [tx {:keys [runtime-id ns] :as msg}]
  (let [runtime (get-in tx [::m/runtime runtime-id])]
    (-> tx
        (assoc-in [::m/runtime runtime-id :explore-ns] ns)
        (cond->
          (not (get-in tx [::m/runtime runtime-id :runtime-vars ns]))
          (ev/queue-fx :relay-send
            [{:op :explore/namespace-vars
              :to (get-target-id-for-runtime runtime)
              :ns ns

              ::relay-ws/result
              {:e ::vars-reply
               :runtime-id runtime-id
               :ns ns}}]
            )))))


(defn describe-var-result!
  {::ev/handle ::describe-var-result!}
  [tx {:keys [runtime-id call-result] :as msg}]
  (assoc-in tx [::m/runtime runtime-id :explore-var-description] (:description call-result)))

(defn deref-var-result!
  {::ev/handle ::deref-var-result!}
  [tx {:keys [runtime-id call-result] :as msg}]

  (let [{:keys [op]} call-result]
    (case op
      :eval-result-ref
      (let [{:keys [ref-oid from]} call-result]
        (-> tx
            (kv/add ::m/object {:oid ref-oid :runtime-id from})
            (assoc-in [::m/runtime runtime-id :explore-var-object] ref-oid)))

      :eval-runtime-error
      (let [{:keys [ex-oid from]} call-result]
        (-> tx
            (kv/add ::m/object
              {:oid ex-oid
               :runtime-id from
               :is-error true})
            (assoc-in [::m/runtime runtime-id :explore-var-object] ex-oid)))

      (throw (ex-info "not handled" msg)))))

(defn runtime-select-var!
  {::ev/handle ::m/runtime-select-var!}
  [tx {:keys [runtime-id var] :as msg}]
  (let [runtime (get-in tx [::m/runtime runtime-id])]

    (-> tx
        (assoc-in [::m/runtime runtime-id :explore-var] var)
        (ev/queue-fx :relay-send
          ;; always fetching these even if we might have them
          ;; might have changed in the meantime with no clean way to tell
          [{:op :explore/describe-var
            :to (get-target-id-for-runtime runtime)
            :var var
            ::relay-ws/result
            {:e ::describe-var-result!
             :runtime-id runtime-id
             :var var}}

           (when-some [op (case (get-in runtime [:runtime-info :lang])
                            :clj :clj-eval
                            :cljs :cljs-eval
                            nil)]
             {:op op
              :to runtime-id ;; always goes directly to runtime
              :input {:ns (symbol (namespace var))
                      :code (name var)}
              ::relay-ws/result
              {:e ::deref-var-result!
               :runtime-id runtime-id
               :var var}})
           ]))))


(defn runtime-deselect-var!
  {::ev/handle ::m/runtime-deselect-var!}
  [tx {:keys [runtime-id] :as msg}]
  (update-in tx [::m/runtime runtime-id] dissoc ::m/explore-var ::m/explore-var-object))