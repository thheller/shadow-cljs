(ns shadow.remote.runtime.obj-support
  (:require
    [clojure.datafy :as d]
    [clojure.pprint :refer (pprint)]
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.writer :as lw])
  #?(:clj (:import [java.util UUID])))

(defrecord Reference [obj])

(defn obj-ref [obj]
  (Reference. obj))

(defn obj-ref? [result]
  (instance? Reference result))

(defn now []
  #?(:clj
     (System/currentTimeMillis)
     :cljs
     (js/Date.now)))

(defn next-oid []
  #?(:clj
     (str (UUID/randomUUID))
     :cljs
     (str (random-uuid))))

(defn register*
  [state oid obj obj-info]

  (let [ts (now)

        obj-entry
        {:oid oid
         :obj obj
         ;; tracking that for GC purposes
         :access-at ts
         :obj-info (assoc obj-info :added-at ts)}]

    (assoc-in state [:objects oid] obj-entry)))

(defn obj-type-string [obj]
  (if (nil? obj)
    "nil"
    #?(:clj
       (-> (class obj) (.getName))
       :cljs
       (pr-str (type obj)))))

(defmulti make-view
  (fn [state-ref {:keys [view-type] :as msg} entry]
    view-type))

;; 1meg?
(def default-max-print-size (* 1 1024 1024))

(defn as-edn
  [data {:keys [limit] :or {limit default-max-print-size} :as msg}]
  (let [lw (lw/limit-writer limit)]
    #?(:clj
       (print-method data lw)
       :cljs
       (pr-writer data lw (pr-opts)))
    (lw/get-string lw)))

(defn as-pprint
  [data {:keys [limit] :or {limit default-max-print-size} :as msg}]
  ;; CLJ pprint for some reason doesn't run out of memory when printing circular stuff
  ;; but it never finishes either
  (let [lw (lw/limit-writer limit)]
    (pprint data lw)
    (lw/get-string lw)))

(defn as-edn-limit
  [data {:keys [limit] :as msg}]
  (lw/pr-str-limit data limit))

(defn attempt-to-sort [desc coll]
  (try
    (-> desc
        (assoc :view-order (vec (sort coll)))
        (assoc-in [:summary :sorted] true))
    (catch #?(:clj Exception :cljs :default) e
      (-> desc
          (assoc :view-order (vec coll))
          (assoc-in [:summary :sorted] false)))))

(defn browseable-kv [{:keys [view-order data] :as desc}]
  (-> desc
      (assoc-in [:handlers :nav]
        (fn [{:keys [idx]}]
          (let [key (nth view-order idx)
                val (get data key)
                nav (d/nav data key val)]
            (obj-ref nav))))
      (assoc-in [:handlers :fragment]
        (fn [{:keys [start num key-limit val-limit]
              :or {key-limit 50
                   val-limit 50}
              :as msg}]

          (let [end (min (count view-order) (+ start num))
                idxs (range start end)
                fragment
                (reduce
                  (fn [m idx]
                    (let [key (nth view-order idx)
                          val (get data key)]
                      (assoc m idx {:key (try
                                           (lw/pr-str-limit key key-limit)
                                           (catch #?(:clj Exception :cljs :default) e
                                             [true "... print failed ..."]))
                                    :val (try
                                           (lw/pr-str-limit val val-limit)
                                           (catch #?(:clj Exception :cljs :default) e
                                             [true "... print failed ..."]))})))
                  {}
                  idxs)]

            fragment)))))

(defn browseable-vec [{:keys [data] :as desc}]
  (-> desc
      (assoc-in [:handlers :nav]
        (fn [{:keys [idx]}]
          (let [val (nth data idx)
                nav (d/nav data idx val)]
            (obj-ref nav))))
      (assoc-in [:handlers :fragment]
        (fn [{:keys [start num key-limit val-limit]
              :or {key-limit 50
                   val-limit 50}
              :as msg}]

          (let [end (min (count data) (+ start num))
                idxs (range start end)
                fragment
                (reduce
                  (fn [m idx]
                    (let [val (nth data idx)]
                      (assoc m idx {:val (lw/pr-str-limit val val-limit)})))
                  {}
                  idxs)]

            fragment)))))

(defn browseable-seq [{:keys [data view-order] :as desc}]
  (-> desc
      (assoc-in [:handlers :nav]
        (fn [{:keys [idx]}]
          (let [val (nth view-order idx)
                nav (d/nav data idx val)]
            (obj-ref nav))))
      (assoc-in [:handlers :fragment]
        (fn [{:keys [start num key-limit val-limit]
              :or {key-limit 50
                   val-limit 50}
              :as msg}]

          (let [end (min (count view-order) (+ start num))
                idxs (range start end)
                fragment
                (reduce
                  (fn [m idx]
                    (let [val (nth view-order idx)]
                      (assoc m idx {:val (lw/pr-str-limit val val-limit)})))
                  {}
                  idxs)]

            fragment)))))

(defn inspect-basic [{:keys [data] :as desc} obj opts]
  (cond
    (nil? data)
    (assoc-in desc [:summary :data-type] :nil)

    (string? data)
    (-> desc
        (update :summary merge {:data-type :string
                                :length (count data)})
        ;; FIXME: substring support?
        (assoc-in [:handlers :get-value] (fn [msg] data)))

    (boolean? data)
    (-> desc
        (assoc-in [:summary :data-type] :boolean)
        (assoc-in [:handlers :get-value] (fn [msg] data)))

    (number? data)
    (-> desc
        (assoc-in [:summary :data-type] :number)
        (assoc-in [:handlers :get-value] (fn [msg] data)))

    (keyword? data)
    (-> desc
        (assoc-in [:summary :data-type] :keyword)
        (assoc-in [:handlers :get-value] (fn [msg] data)))

    (symbol? data)
    (-> desc
        (assoc-in [:summary :data-type] :symbol)
        (assoc-in [:handlers :get-value] (fn [msg] data)))

    (map? data)
    (-> desc
        (update :summary merge {:data-type :map
                                :entries (count data)})
        (attempt-to-sort (keys data))
        (browseable-kv))

    (vector? data)
    (-> desc
        (update :summary merge {:data-type :vec
                                :entries (count data)})
        (browseable-vec))

    (set? data)
    (-> data
        (update :summary merge {:data-type :set
                                :entries (count data)})
        (attempt-to-sort data)
        (browseable-seq))

    ;; FIXME: lazy seqs / other seqs / records
    :else
    (assoc-in desc [:summary :data-type] :unsupported)))

(defn inspect-type-info [desc obj opts]
  (assoc-in desc [:summary :obj-type] (obj-type-string obj)))

(defn inspect-source-info [desc obj opts]
  (update desc :summary merge (select-keys opts [:ns :line :column :label])))

(defn add-summary-op [{:keys [summary] :as desc}]
  (assoc-in desc [:handlers :summary] (fn [msg] summary)))

(defn default-describe [o opts]
  (let [data (d/datafy o)]

    (-> {:data data
         :summary
         {:added-at (:added-at opts)
          :datafied (not (identical? data o))}

         ;; FIXME: only add those for clojure values
         ;; often pointless when datafy returned original object
         :handlers
         {:edn-limit #(as-edn-limit data %)
          :edn #(as-edn data %)
          :pprint #(as-pprint data %)}}

        (inspect-basic o opts)
        (inspect-type-info o opts)
        (inspect-source-info o opts)
        (add-summary-op))))

(extend-protocol p/Inspectable
  #?(:clj Object :cljs default)
  (describe [o opts]
    (default-describe o opts)))

(comment
  (p/describe
    {:hello "world"}
    {:added-at "NOW" :ns "foo.bar"}))

;; called after describe so impls don't have to worry about this
(defn add-supports [{:keys [handlers] :as desc}]
  (assoc-in desc [:summary :supports] (set (keys handlers))))

;; FIXME: this is running inside swap! which means it can potentially
;; end up getting executed several times for the same object (in CLJ)
;; that is not great and should be handled differently
(defn ensure-descriptor [{:keys [obj obj-info desc] :as entry}]
  (if desc
    entry
    (assoc entry :desc (-> (p/describe obj obj-info)
                           (add-supports)))))


(defn obj-describe
  [{:keys [state-ref runtime]}
   {:keys [oid] :as msg}]
  (if-not (contains? (:objects @state-ref) oid)
    (p/reply runtime msg {:op :obj-not-found :oid oid})
    (do (swap! state-ref update-in [:objects oid] ensure-descriptor)
        (swap! state-ref assoc-in [:objects oid :access-at] (now))
        (let [summary (get-in @state-ref [:objects oid :desc :summary])]
          (p/reply runtime msg {:op :obj-summary
                                :oid oid
                                :summary summary})))))

(defn obj-request
  [{:keys [state-ref runtime]}
   {:keys [oid request-op] :as msg}]
  (if-not (contains? (:objects @state-ref) oid)
    (p/reply runtime msg {:op :obj-not-found :oid oid})
    (do (swap! state-ref update-in [:objects oid] ensure-descriptor)
        (swap! state-ref assoc-in [:objects oid :access-at] (now))
        (let [entry (get-in @state-ref [:objects oid])
              request-fn (get-in entry [:desc :handlers request-op])]
          (if-not request-fn
            (p/reply runtime msg {:op :obj-request-not-supported
                                  :oid oid
                                  :request-op request-op})
            (try
              (let [result (request-fn msg)]

                ;; FIXME: add support for generic async results
                ;; all handlers should already be sync but allow async results
                (if-not (obj-ref? result)
                  (p/reply runtime msg {:op :obj-result
                                        :oid oid
                                        :result result})

                  (let [new-oid (next-oid)
                        ts (now)

                        new-entry
                        {:oid new-oid
                         :obj (:obj result)
                         :access-at ts
                         ;; FIXME: should keep some info on how this ref came to be
                         :obj-info {:added-at ts
                                    :added-via oid}}]

                    (swap! state-ref assoc-in [:objects new-oid] new-entry)

                    (p/reply runtime msg {:op :obj-result-ref
                                          :oid oid
                                          :ref-oid new-oid}))))

              (catch #?(:clj Exception :cljs :default) e
                #?(:cljs (js/console.warn "action-request-action failed" (:obj entry) e))
                (p/reply runtime msg {:op :obj-request-failed
                                      :oid oid
                                      :msg msg
                                      ;; FIXME: (d/datafy e) doesn't work for CLJS
                                      :e (str e) #_#?(:clj  (.toString e)
                                                      :cljs (.-message e))})))))
        )))

(defn basic-gc! [state]
  (let [objs-to-drop
        (->> (:objects state)
             (vals)
             (sort-by :access-at)
             (reverse)
             (drop 100) ;; FIXME: make configurable
             (map :oid))]

    (reduce
      (fn [state oid]
        (update state :objects dissoc oid))
      state
      objs-to-drop)))

(defn start [runtime]
  (let [state-ref (atom {:objects {}
                         :id-seq-ref 0})

        svc
        {:runtime runtime
         :state-ref state-ref}]

    (p/add-extension runtime
      ::ext
      {:ops {:obj-describe #(obj-describe svc %)
             :obj-request #(obj-request svc %)}
       :on-idle #(swap! state-ref basic-gc!)})

    svc))

(defn register [{:keys [state-ref] :as svc} obj obj-info]
  (let [oid (next-oid)]
    (if-not (and (vector? obj) (= :shadow.remote/wrap (first obj)) (= (count obj) 3))
      (swap! state-ref register* oid obj obj-info)
      (swap! state-ref register* oid (nth obj 1) (merge obj-info (nth obj 2))))
    oid))

(defn get-ref [{:keys [state-ref]} obj-id]
  (get-in @state-ref [:objects obj-id]))

(defn stop [{:keys [runtime]}]
  (p/del-extension runtime ::ext))

(comment
  (def obj-support (:clj-runtime-obj-support (shadow.cljs.devtools.server.runtime/get-instance)))
  (swap! (:state-ref obj-support) assoc :objects {})
  )