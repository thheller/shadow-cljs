(ns shadow.cljs.ui.db.inspect
  (:require
    [clojure.string :as str]
    [shadow.grove :as sg]
    [shadow.grove.events :as ev]
    [shadow.grove.kv :as kv]
    [shadow.cljs :as-alias m]
    [shadow.cljs.ui.db.relay-ws :as relay-ws]
    [shadow.grove.runtime :as rt])
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

(defn relay-clients
  {::ev/handle ::relay-ws/clients}
  [{:keys [db] :as env} {:keys [clients] :as msg}]
  (let [runtimes
        (->> clients
             (mapv (fn [{:keys [client-id client-info]}]
                     {:runtime-id client-id
                      :runtime-info client-info})))]

    (-> env
        (kv/merge-seq ::m/runtime runtimes [::m/ui ::m/runtimes])
        (ev/queue-fx
          :relay-send
          [{:op :request-supported-ops
            :to (->> clients
                     (map :client-id)
                     (into #{}))}]))))

(defn relay-notify
  {::ev/handle ::relay-ws/notify}
  [{:keys [db] :as env}
   {:keys [event-op client-id client-info]}]
  (case event-op
    :client-connect
    (let [runtime {:runtime-id client-id
                   :runtime-info client-info
                   :eval-history []}]
      (-> env
          (kv/add ::m/runtime runtime)
          (ev/queue-fx :relay-send [{:op :request-supported-ops :to client-id}])))

    :client-disconnect
    (assoc-in env [::m/runtime client-id :disconnected] true)))

(defn relay-supported-ops
  {::ev/handle ::relay-ws/supported-ops}
  [{:keys [db] :as env} {:keys [ops from]}]
  (-> env
      (assoc-in [::m/runtime from :supported-ops] ops)
      (cond->
        (contains? ops :tap-subscribe)
        (ev/queue-fx :relay-send [{:op :tap-subscribe :to from :history true :num 50}])
        )))

(defn guess-display-type [{:keys [db] :as env} {:keys [data-type supports] :as summary}]
  (let [pref (get-in env [::m/ui ::m/preferred-display-type] :browse)]

    (cond
      (and (= :pprint pref) (contains? supports :obj-pprint))
      :pprint

      (and (= :browse pref) (contains? supports :obj-fragment))
      :browse

      (= :edn pref)
      :edn

      (= :edn-pretty pref)
      :edn-pretty

      (contains? supports :obj-fragment)
      :browse

      (contains? #{:string :number :boolean} data-type)
      :edn

      :default
      :edn-pretty
      )))

(defn relay-tap-subscribed
  {::ev/handle ::relay-ws/tap-subscribed}
  [env {:keys [from history] :as msg}]
  (reduce
    (fn [env {:keys [oid summary]}]
      (-> env
          (kv/add ::m/object {:oid oid
                              :runtime-id from
                              :summary (with-added-at-ts summary)
                              :display-type (guess-display-type env summary)})

          ;; FIXME: should do some kind of sorting here
          ;; when loading the UI the runtimes may already had a bunch of taps
          ;; but the tap-subscribed event may arrive in random order
          ;; and tap stream display ends up more or less random
          ;; not a big deal for now but should be fixed eventually
          (update-in [::m/ui ::m/tap-stream] conj oid)))
    env
    (reverse history)))

(defn relay-tap
  {::ev/handle ::relay-ws/tap}
  [env {:keys [oid from] :as msg}]
  (-> env
      (kv/add ::m/object {:oid oid :runtime-id from})
      (update-in [::m/ui ::m/tap-stream] conj oid)
      (assoc-in [::m/ui ::m/tap-latest] oid)))

(defn tap-clear!
  {::ev/handle ::m/tap-clear!}
  [env msg]
  ;; FIXME: this only clears locally, runtimes still have all
  ;; reloading the UI will thus restore them
  (let [tap-stream (get-in env [::m/ui ::m/tap-stream])]
    (reduce
      (fn [env oid]
        (update env ::m/object dissoc oid))
      (assoc-in env [::m/ui ::m/tap-stream] (list))
      tap-stream)))

(defn relay-obj-summary
  {::ev/handle ::relay-ws/obj-summary}
  [env {:keys [from oid summary]}]
  (let [display-type (get-in env [::m/object oid :display-type])]
    (-> env
        (update-in [::m/object oid] merge
          {:summary (with-added-at-ts summary)
           :oid oid
           :runtime-id from})
        (cond->
          (nil? display-type)
          (assoc-in [::m/object oid :display-type] (guess-display-type env summary))))))

(defn maybe-load-summary
  [env {:keys [oid runtime-id summary] :as current}]
  (when-not summary
    (relay-ws/cast!
      (::sg/runtime-ref env)
      {:op :obj-describe
       :to runtime-id
       :oid oid})))

(defn obj-as-result
  {::ev/handle ::obj-as-result}
  [env {:keys [oid call-result key] :as res}]
  (let [{:keys [op result]} call-result]
    (case op
      :obj-result
      (assoc-in env [::m/object oid key] result)

      :obj-request-failed
      (update-in env [::m/object oid] merge
        {key ::m/display-error!
         :ex-oid (:ex-oid call-result)
         :ex-client-id (:from call-result)})

      (throw (ex-info "unexpected result for obj-request" res))
      )))

(defn maybe-load-object-as
  [env object op]
  (when-not (get object op)
    (let [{:keys [oid runtime-id]} object]
      (relay-ws/call!
        (::sg/runtime-ref env)
        {:op op
         :to runtime-id
         :oid oid}

        {:e ::obj-as-result
         :oid oid
         :key op}))))

(defn fragment-vlist [env oid {:keys [offset num] :or {offset 0 num 0} :as params}]

  (let [{:keys [runtime-id summary fragment] :as object}
        (get-in env [::m/object oid])

        {:keys [data-count]} summary

        start-idx offset
        last-idx (js/Math.min data-count (+ start-idx num))

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
      {:item-count data-count
       :offset offset
       :slice (persistent! slice)}

      ;; missing elements
      ;; FIXME: should be smarter about which elements to fetch
      ;; might already have some
      (do (relay-ws/call!
            (::sg/runtime-ref env)
            {:op :obj-fragment
             :to runtime-id
             :oid oid
             :start start-idx
             :num num
             :key-limit 160
             :val-limit 160}
            {:e ::fragment-slice-loaded
             :oid oid})

          (sg/suspend! {:item-count 0})))))

(defn tap-vlist
  [env
   {:keys [offset num] :or {offset 0 num 0} :as params}]

  (let [tap-stream
        (get-in env [::m/ui ::m/tap-stream])

        entries
        (count tap-stream)

        slice
        (->> tap-stream
             (drop offset)
             (take num)
             (vec))]

    {:item-count entries
     :offset offset
     :slice slice}
    ))

(defn fragment-slice-loaded
  {::ev/handle ::fragment-slice-loaded}
  [env {:keys [oid call-result]}]
  (let [{:keys [op result]} call-result]
    (assert (= :obj-result op)) ;; FIXME: handle failures
    (update-in env [::m/object oid :fragment] merge result)))

(defn lazy-seq-vlist
  [env
   {:keys [oid runtime-id summary realized more? fragment] :as current}
   {:keys [offset num] :or {offset 0 num 0} :as params}]

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

      (do (relay-ws/call!
            (::sg/runtime-ref env)
            {:op :obj-lazy-chunk
             :to runtime-id
             :oid oid
             :start start-idx
             :num num
             :val-limit 100}

            {:e ::lazy-seq-slice-loaded
             :oid oid})

          (sg/suspend! {:item-count 0})))))

(defn lazy-seq-slice-loaded
  {::ev/handle ::lazy-seq-slice-loaded}
  [env {:keys [oid call-result]}]
  (let [{:keys [op realized fragment more?]} call-result]
    (assert (= :obj-result op)) ;; FIXME: handle failures
    (-> env
        (assoc-in [::m/object oid :realized] realized)
        (assoc-in [::m/object oid :more?] more?)
        (update-in [::m/object oid :fragment] merge fragment))))

(defn inspect-object!
  {::ev/handle ::m/inspect-object!}
  [env {:keys [oid keep-idx]}]
  (let [{:keys [summary runtime-id] :as object} (get-in env [::m/object oid])]
    (let [stack
          (-> (get-in env [::m/ui ::m/inspect :stack])
              (subvec 0 (or keep-idx 1))
              (conj {:type :object-panel
                     :oid oid}))]

      (-> env
          (update-in [::m/ui ::m/inspect] merge
            {:stack stack
             :current (dec (count stack))})
          (cond->
            (not summary)
            (ev/queue-fx :relay-send
              [{:op :obj-describe
                :to runtime-id
                :oid oid}])
            )))))

(defn inspect-nav!
  {::ev/handle ::m/inspect-nav!}
  [env {:keys [oid idx panel-idx]}]
  (let [{:keys [runtime-id] :as object} (get-in env [::m/object oid])]

    ;; FIXME: fx this
    (relay-ws/call!
      (::sg/runtime-ref env)
      {:op :obj-nav
       :to runtime-id
       :oid oid
       :idx idx
       :summary true}

      {:e ::inspect-nav-result
       :oid oid
       :idx idx
       :panel-idx panel-idx})

    env))

(defn inspect-nav-result
  {::ev/handle ::inspect-nav-result}
  [env {:keys [idx panel-idx call-result] :as tx}]

  (case (:op call-result)
    :obj-result
    env

    ;; FIXME: decide if :obj-result should do anything
    ;; just returning env when the nav returns :obj-result instead of :obj-result-ref
    ;; it returns :obj-result for simple values such as nil, boolean, empty colls, etc
    ;; the assumption is that the current display already showed sufficient info
    ;; to make an extra panel redundant
    ;; we could maybe guess this based on the fragment value we get when displaying the results
    ;; but nav may turn a simple 1 into a db lookup and return actual map
    ;; so need to ask remote to confirm first
    #_(update env :db
        (fn [db]
          (let [{:keys [result]}
                call-result

                {:keys [stack]}
                (::m/inspect db)

                stack
                (-> (subvec stack 0 (inc panel-idx))
                    (conj {:type :local-object-panel
                           :value result}))]

            (-> db
                (assoc-in [::m/inspect :stack] stack)
                (assoc-in [::m/inspect :current] (inc panel-idx))))))

    :obj-result-ref
    (let [{:keys [oid nav? ref-oid from summary]}
          call-result

          obj
          {:oid ref-oid
           :runtime-id from
           :summary summary
           :display-type (guess-display-type env summary)}

          stack
          (get-in env [::m/ui ::m/inspect :stack])

          stack
          (-> (subvec stack 0 (inc panel-idx))
              (conj {:type :object-panel
                     :oid ref-oid
                     :nav? nav?
                     :nav-from oid
                     :nav-idx idx}))]

      (-> env
          (kv/add ::m/object obj)
          (assoc-in [::m/ui ::m/inspect :stack] stack)
          (assoc-in [::m/ui ::m/inspect :current] (inc panel-idx))))))

(defn inspect-set-current!
  {::ev/handle ::m/inspect-set-current!}
  [env {:keys [idx]}]
  (assoc-in env [::m/ui ::m/inspect :current] idx))

(defn inspect-nav-jump!
  {::ev/handle ::m/inspect-nav-jump!}
  [env {:keys [idx]}]
  (let [idx (inc idx)]
    (update-in env [::m/ui ::m/inspect :nav-stack] subvec 0 idx)))

(defn inspect-switch-display!
  {::ev/handle ::m/inspect-switch-display!}
  [env {:keys [oid display-type]}]
  (assoc-in env [::m/object oid :display-type] display-type))

(defn inspect-code-eval!
  {::ev/handle ::m/inspect-code-eval!}
  [tx {:keys [code oid panel-idx] :as msg}]
  (let [{:keys [runtime-id] :as object}
        (get-in tx [::m/object oid])

        eval-mode
        (if (= 1 runtime-id) :clj-eval :cljs-eval)

        input
        {:ns 'shadow.user
         :code code
         :obj-refs [oid]}]

    (ev/queue-fx tx :relay-send
      [{:op eval-mode
        :to runtime-id
        :input input
        ::relay-ws/result
        {:e ::inspect-eval-result!
         :code code
         :panel-idx panel-idx}}]
      )))

(defn inspect-eval-result!
  {::ev/handle ::inspect-eval-result!}
  [env {:keys [code panel-idx call-result]}]
  (case (:op call-result)
    :eval-result-ref
    (let [{:keys [ref-oid from warnings]} call-result]
      (when (seq warnings)
        (doseq [w warnings]
          (js/console.warn "FIXME: warning not yet displayed in UI" w)))

      (-> env
          (kv/add ::m/object {:oid ref-oid :runtime-id from})
          (assoc-in [::m/ui ::m/inspect :current] (inc panel-idx))
          (update-in [::m/ui ::m/inspect :stack]
            (fn [stack]
              (-> stack
                  (subvec 0 (inc panel-idx))
                  (conj {:type :object-panel
                         :label code
                         :oid ref-oid}))))))

    :eval-compile-error
    (let [{:keys [from ex-oid ex-client-id]} call-result]
      (-> env
          (kv/add ::m/object {:oid ex-oid
                              :runtime-id (or ex-client-id from)
                              :is-error true})
          (update-in [::m/ui ::m/inspect :stack] conj {:type :object-panel :oid ex-oid :label code})
          (update-in [::m/ui ::m/inspect :current] inc)))

    :eval-compile-warnings
    (do (js/console.log "there were some warnings" call-result)
        env)

    :eval-runtime-error
    (let [{:keys [from ex-oid]} call-result]
      (-> env
          (kv/add ::m/object {:oid ex-oid
                              :runtime-id from
                              :is-error true})
          (update-in [::m/ui ::m/inspect :stack] conj {:type :object-panel :oid ex-oid :label code})
          (update-in [::m/ui ::m/inspect :current] inc)))))

(defn inspect-def-as!
  {::ev/handle ::m/inspect-def-as!}
  [tx {:keys [oid runtime-id panel-idx name] :as msg}]
  (let [stack
        (get-in tx [::m/ui ::m/inspect :stack])

        stack
        (-> (subvec stack 0 (inc panel-idx))
            (conj {:type :repl-panel
                   :stream-id oid}))]
    (-> tx
        (assoc-in [::m/ui ::m/inspect :stack] stack)
        (assoc-in [::m/ui ::m/inspect :current] (inc panel-idx))
        (sg/queue-fx :relay-send
          [{:op ::m/repl-stream-start!
            :to 1
            :stream-id oid
            :target runtime-id
            :target-op (if (= 1 runtime-id) :clj-eval :cljs-eval)
            :target-ns 'shadow.user}
           {:op ::m/repl-stream-input!
            :to 1
            :stream-id oid
            :code (str "(def " name " (shadow.remote.runtime.eval-support/get-ref \"" oid "\"))")}]))))