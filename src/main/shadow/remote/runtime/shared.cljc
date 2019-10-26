(ns shadow.remote.runtime.shared
  (:require
    [clojure.datafy :as d]
    [clojure.pprint :refer (pprint)]
    [shadow.remote.runtime.writer :as lw])
  #?(:clj (:import [java.util UUID])))

;; runtime related code
;; no network stuff at all
;; might need some kind of async support since `nav` won't likely
;; be sync in JS all the time but we can make it work by just
;; checking the return value and "wait" for the proper result if needed

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

(defn init-state []
  {:objects {}
   :tap-subs #{}})

(defn register*
  [state oid obj obj-info]

  (let [data (d/datafy obj)
        datafied (not (identical? data obj))

        ts (now)

        obj-entry
        {:oid oid
         :obj obj
         :obj-info obj-info ;; FIXME: just merge this?
         :data data
         :added-at ts
         :access-at ts
         :datafied datafied}]

    (assoc-in state [:objects oid] obj-entry)))

(defn register [state-ref obj obj-info]
  (let [oid (next-oid)]
    (swap! state-ref register* oid obj obj-info)
    oid))

(defn obj-type-string [obj]
  (if (nil? obj)
    "nil"
    #?(:clj
       (-> (class obj) (.getName))
       :cljs
       (pr-str (type obj)))))

(defmulti process
  (fn [state-ref {:keys [op] :as msg} reply]
    op)
  :default ::default)

(defmethod process ::default [state-ref msg reply]
  (reply {:op :unknown-op
          :request-op (:op msg)}))

(defmethod process :welcome [state-ref {:keys [rid] :as msg} reply]
  #?(:cljs (js/console.log "shadow.remote - runtime-id:" rid))
  (swap! state-ref assoc :runtime-id rid))

(defmulti make-view
  (fn [state-ref {:keys [view-type] :as msg} entry]
    view-type))

;; 1meg?
(def default-max-print-size (* 1 1024 1024))

(defmethod make-view :edn
  [state-ref
   {:keys [limit] :or {limit default-max-print-size} :as msg}
   {:keys [data] :as entry}]
  (let [lw (lw/limit-writer limit)]
    #?(:clj
       (print-method data lw)
       :cljs
       (pr-writer data lw (pr-opts)))
    (lw/get-string lw)))

(defmethod make-view :pprint
  [state-ref
   {:keys [limit] :or {limit default-max-print-size} :as msg}
   {:keys [data] :as entry}]
  ;; CLJ pprint for some reason doesn't run out of memory when printing circular stuff
  ;; but it never finishes either
  (let [lw (lw/limit-writer limit)]
    (pprint data lw)
    (lw/get-string lw)))

(defmethod make-view :edn-limit
  [state-ref {:keys [limit] :as msg} {:keys [data] :as entry}]
  (lw/pr-str-limit data limit))

(defn attempt-to-sort [coll m]
  (try
    (assoc m
      :view-keys (vec (sort coll))
      :sorted true)
    (catch #?(:clj Exception :cljs :default) e
      (assoc m
        :view-keys (vec coll)
        :sorted false))))

(defn make-summary* [{:keys [obj data datafied] :as entry}]
  ;; FIXME: could be a protocol or multimethod
  ;; but I kinda want to enforce limiting this to standard clojure
  ;; types and rather have people implement datafy than this
  (-> (cond
        (nil? data)
        {:data-type :nil}

        (string? data)
        ;; FIXME: :long-string support so it doesn't send a 5MB string
        {:data-type :string
         :value data}

        (boolean? data)
        {:data-type :boolean
         :value data}

        (number? data)
        {:data-type :number
         :value data}

        (keyword? data)
        {:data-type :keyword
         :value data}

        (symbol? data)
        {:data-type :symbol
         :value data}

        (map? data)
        (attempt-to-sort
          (keys data)
          {:data-type :map
           :count (count data)})

        (vector? data)
        {:data-type :vec
         :count (count data)
         ;; FIXME: seems like overkill to allocate a potentially large vec of numbers
         ;; should just have special handling to work on the vector directly maybe?
         :view-keys (vec (range 0 (count data)))}

        (set? data)
        (attempt-to-sort
          data
          {:data-type :set
           :count (count data)})

        ;; FIXME: lazy seqs / other seqs / records
        :else
        {:data-type :unsupported})

      (assoc :obj-type (obj-type-string obj)
             :datafied datafied
             :added-at (:added-at entry)
             ;; FIXME: meta from obj or data?
             )))

(defn make-summary [state-ref obj {:keys [oid data summary] :as entry}]
  (or summary
      (let [summary (make-summary* entry)]
        (swap! state-ref assoc-in [:objects oid :summary] summary)
        summary)))

(defmethod make-view :summary
  [state-ref msg entry]

  (let [summary (make-summary state-ref msg entry)]
    (dissoc summary :view-keys)))

(defmethod make-view :fragment
  [state-ref
   {:keys [start num key-limit val-limit]
    :or {key-limit 50
         val-limit 50}
    :as msg}
   {:keys [data] :as entry}]

  (let [{:keys [data-type view-keys] :as summary}
        (make-summary state-ref msg entry)]

    (case data-type
      (:vec :set)
      (let [end (min (:count summary) (+ start num))
            idxs (range start end)
            fragment
            (reduce
              (fn [m idx]
                (let [key (nth view-keys idx)
                      val (get data key)]
                  (assoc m idx {:val (lw/pr-str-limit val val-limit)})))
              {}
              idxs)]

        fragment)

      :map
      (let [end (min (:count summary) (+ start num))
            idxs (range start end)
            fragment
            (reduce
              (fn [m idx]
                (let [key (nth view-keys idx)
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

        fragment)

      {}
      )))

(defmethod process :tap-subscribe [state-ref {:keys [tid] :as msg} reply]
  (swap! state-ref update :tap-subs conj tid))

(defmethod process :tap-unsubscribe [state-ref {:keys [tid] :as msg} reply]
  (swap! state-ref update :tap-subs disj tid))

(defmethod process :request-tap-history
  [state-ref {:keys [num] :or {num 10} :as msg} reply]
  (let [tap-ids
        (->> (:objects @state-ref)
             (vals)
             (filter #(= :tap (get-in % [:obj-info :from])))
             (sort-by :added-at)
             (reverse)
             (take num)
             (map :oid)
             (into []))]

    (reply {:op :tap-history
            :oids tap-ids})))

(defmethod process :tool-disconnect [state-ref {:keys [tid] :as msg} reply]
  (swap! state-ref update :tap-subs disj tid))

(defmethod process :obj-request-view
  [state-ref {:keys [oid view-type] :as msg} reply]

  (swap! state-ref assoc-in [:objects oid :access-at] (now))

  (let [state @state-ref
        entry (get-in state [:objects oid])]
    (if-not entry
      (reply {:op :obj-not-found :oid oid})
      (try
        (let [view (make-view state-ref msg entry)]
          (reply {:op :obj-view
                  :oid oid
                  :view-type view-type
                  :view view}))
        (catch #?(:clj Exception :cljs :default) e
          #?(:cljs (js/console.warn "object-nav-failed" (:obj entry) e))
          (reply {:op :obj-view-failed
                  :oid oid
                  :view-type view-type
                  ;; FIXME: (d/datafy e) doesn't work for CLJS
                  :e (str e)#_ #?(:clj (.toString e)
                        :cljs (.-message e))}))))))

(defmethod process :obj-request-nav
  [state-ref {:keys [oid idx] :as msg} reply]

  (swap! state-ref assoc-in [:objects oid :access-at] (now))

  (let [state @state-ref
        entry (get-in state [:objects oid])]
    (if-not entry
      (reply {:op :obj-not-found :oid oid})
      (let [{:keys [obj data summary]} entry]
        (if-not summary
          (reply {:op :obj-nav-not-supported :oid oid})

          (let [{:keys [view-keys]} summary
                data-key (nth view-keys idx)
                data-val (get data data-key)

                ;; FIXME: async support, if val is a promise or something
                ;; it should reply with a `:nav-async` message first and
                ;; send another reply later when the result is actually available
                ;; or should this be handled by datafy on Promise? pull vs push
                ;; FIXME: how to handle failure? this could throw
                val (d/nav data data-key data-val)

                new-data (d/datafy val)
                new-oid (next-oid)

                ts (now)

                new-entry
                {:oid new-oid
                 :obj val
                 :data new-data
                 :added-at ts
                 :access-at ts
                 :nav-from oid
                 :nav-key data-key
                 :nav-idx idx
                 :datafied (not (identical? val new-data))}]

            (swap! state-ref assoc-in [:objects new-oid] new-entry)

            (reply {:op :obj-nav-success
                    :oid oid
                    :nav-oid new-oid})))))))

(defmethod process :request-supported-ops
  [state-ref msg reply]
  (reply {:op :supported-ops
          :ops (-> (methods process)
                   (keys)
                   (set)
                   (disj ::default))}))

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

(defn basic-gc [state-ref]
  (swap! state-ref basic-gc!))