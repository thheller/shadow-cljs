(ns shadow.remote.runtime.obj-support
  (:require
    [clojure.datafy :as d]
    [clojure.pprint :refer (pprint)]
    [clojure.spec.alpha :as spec]
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.writer :as lw]
    ;; FIXME: I do not like importing these here
    ;; need to extract shadow-cljs functions if I ever move shadow.remote out
    ;; cljs.repl has way too much other stuff on the CLJ side not error related we don't really need here
    ;; should just have one namespace only concerned with formatting errors
    ;; maybe even as separate plugin
    #?@(:clj [[shadow.cljs.devtools.errors :refer (error-format)]
              [shadow.jvm-log]]
        :cljs [[cljs.repl :refer (error->str)]]))
  #?(:clj (:import [java.util UUID])))

(defrecord Reference [obj])

(defn obj-ref [obj]
  (when (some? obj)
    (Reference. obj)))

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

(declare register)

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

;; FIXME: should likely support limit options
(defn as-str
  [data msg]
  (str data))

(defn as-ex-str [ex msg]
  #?(:cljs
     (if (instance? js/Error ex)
       (error->str ex)
       (str "Execution error:\n"
            ;; can be any object, really no hope in making this any kind of readable
            ;; capping it so throwing something large doesn't blow up the REPL
            "  " (second (lw/pr-str-limit ex 200)) "\n"
            "\n"))

     :clj
     (error-format ex)))

(defn exception? [x]
  #?(:clj (instance? java.lang.Throwable x)
     ;; everything can be thrown in JS
     ;; (throw "x")
     ;; (throw (js/Promise.resolved "x"))
     :cljs true ;; (instance? js/Error x)
     ))

(def rank-predicates
  [nil?
   boolean?
   number?
   string?
   keyword?
   symbol?
   vector?
   map?
   list?])

(defn rank-val [val]
  (reduce-kv
    (fn [res idx pred]
      (if (pred val)
        (reduced idx)
        res))
    -1
    rank-predicates))

(defn smart-comp [a b]
  (try
    (compare a b)
    (catch #?(:clj Exception :cljs js/Error) e
      (let [ar (rank-val a)
            br (rank-val b)]
        (compare ar br)))))

(defn attempt-to-sort [desc coll]
  (try
    (-> desc
        (assoc :view-order (vec (sort smart-comp coll)))
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
              :or {key-limit 100
                   val-limit 100}
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
        (fn [{:keys [start num val-limit]
              :or {val-limit 100}
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
        (fn [{:keys [start num val-limit]
              :or {val-limit 100}
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

(defn pageable-seq [{:keys [data] :as desc}]
  ;; data is always beginning of seq
  (let [seq-state-ref
        (atom {:tail data ;; track where we are at
               :realized []})]
    (-> desc
        (assoc :seq-state-ref seq-state-ref)
        (assoc-in [:handlers :nav]
          (fn [{:keys [idx]}]
            ;; FIXME: should validate that idx is actually realized
            (let [val (nth (:realized @seq-state-ref) idx)
                  ;; FIXME: not sure there are many cases where lazy seqs actually have nav?
                  nav (d/nav data idx val)]
              (obj-ref nav))))
        (assoc-in [:handlers :chunk]
          (fn [{:keys [start num val-limit]
                :or {val-limit 100}
                :as msg}]

            ;; need locking otherwise threads may realize more than once
            ;; shouldn't be much of an issue but better be safe
            (locking seq-state-ref
              (let [{:keys [tail realized] :as seq-state} @seq-state-ref

                    end (+ start num)
                    missing (- end (count realized))

                    [tail realized]
                    (loop [tail tail
                           realized realized
                           missing missing]
                      (if-not (pos? missing)
                        [tail realized]
                        (let [next (first tail)]
                          (if (nil? next)
                            [nil realized]
                            (recur (rest tail) (conj realized next) (dec missing))))))

                    idxs (range start (min end (count realized)))
                    fragment
                    (reduce
                      (fn [m idx]
                        (let [val (nth realized idx)]
                          (assoc m idx {:val (lw/pr-str-limit val val-limit)})))
                      {}
                      idxs)]

                (swap! seq-state-ref assoc :tail tail :realized realized)

                {:start start
                 :realized (count realized)
                 :fragment fragment
                 :more? (or (> (count realized) end) (some? tail))})))))))

(comment
  (def x (pageable-seq {:data (map (fn [x] (prn [:realize x]) x) (range 10))}))

  (let [chunk (get-in x [:handlers :chunk])]
    (chunk {:start 0 :num 5})
    )

  (let [chunk (get-in x [:handlers :chunk])]
    (chunk {:start 5 :num 10})
    ))

(defn inspect-basic [{:keys [data] :as desc} obj opts]
  (try
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
      (-> desc
          (update :summary merge {:data-type :set
                                  :entries (count data)})
          (attempt-to-sort data)
          (browseable-seq))

      (list? data)
      (-> desc
          (update :summary merge {:data-type :list
                                  :entries (count data)})
          (assoc :view-order (vec data))
          (browseable-seq))

      ;; lazy seqs
      (seq? data)
      (-> desc
          (update :summary merge {:data-type :lazy-seq})
          (pageable-seq))

      ;; FIXME: records?

      :else
      (assoc-in desc [:summary :data-type] :unsupported))

    (catch #?(:cljs :default :clj Exception) e
      (assoc-in desc [:summary :data-type] :unsupported))))

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

         ;; FIXME: should these work on the datafy result or the original?
         ;; maybe different ops? maybe msg option?
         :handlers
         (-> {:str #(as-str o %)
              ;; FIXME: only do those for actual clojure vals?
              :edn #(as-edn o %)
              :edn-limit #(as-edn-limit o %)}
             (cond->
               (or (coll? o) (seq? o))
               (assoc :pprint #(as-pprint o %))

               (exception? o)
               (assoc :ex-str #(as-ex-str o %))
               ))}

        (inspect-basic o opts)
        (inspect-type-info o opts)
        (inspect-source-info o opts)
        (add-summary-op))))

(extend-protocol p/Inspectable
  #?(:clj Object :cljs default)
  (describe [o opts]
    (default-describe o opts))

  nil
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

(defn get-tap-history [{:keys [state-ref] :as svc} num]
  (->> (:objects @state-ref)
       (vals)
       (filter #(= :tap (get-in % [:obj-info :from])))
       (sort-by #(get-in % [:obj-info :added-at]))
       (reverse)
       (take num)
       (map :oid)
       (into [])))

(defn obj-describe*
  [{:keys [state-ref]}
   oid]
  (when (contains? (:objects @state-ref) oid)
    (swap! state-ref update-in [:objects oid] ensure-descriptor)
    (swap! state-ref assoc-in [:objects oid :access-at] (now))
    (let [summary (get-in @state-ref [:objects oid :desc :summary])]
      summary)))

(defn obj-describe
  [{:keys [runtime] :as svc}
   {:keys [oid] :as msg}]
  (if-let [summary (obj-describe* svc oid)]
    (shared/reply runtime msg {:op :obj-summary
                               :oid oid
                               :summary summary})
    (shared/reply runtime msg {:op :obj-not-found :oid oid})))

(defn obj-request
  [{:keys [state-ref runtime] :as this}
   {:keys [oid request-op] :as msg}]
  (if-not (contains? (:objects @state-ref) oid)
    (shared/reply runtime msg {:op :obj-not-found :oid oid})
    (do (swap! state-ref update-in [:objects oid] ensure-descriptor)
        (swap! state-ref assoc-in [:objects oid :access-at] (now))
        (let [entry (get-in @state-ref [:objects oid])
              request-fn (get-in entry [:desc :handlers request-op])]
          (if-not request-fn
            (shared/reply runtime msg {:op :obj-request-not-supported
                                       :oid oid
                                       :request-op request-op})
            (try
              (let [result (request-fn msg)]

                ;; FIXME: add support for generic async results
                ;; all handlers should already be sync but allow async results
                (if-not (obj-ref? result)
                  (shared/reply runtime msg
                    {:op :obj-result
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

                    (let [reply-msg
                          (-> {:op :obj-result-ref
                               :oid oid
                               :ref-oid new-oid}
                              (cond->
                                ;; only send new-obj :summary when requested
                                (:summary msg)
                                (assoc :summary (obj-describe* this new-oid))))]

                      (shared/reply runtime msg reply-msg)))))

              (catch #?(:clj Exception :cljs :default) e
                #?(:cljs (js/console.warn "action-request-action failed" (:obj entry) e)
                   :clj (shadow.jvm-log/warn-ex e ::obj-request-failed msg))
                (shared/reply runtime msg
                  {:op :obj-request-failed
                   :oid oid
                   :msg msg
                   :ex-oid (register this e {:msg msg})})))))
        )))

(defn obj-forget
  [{:keys [state-ref] :as svc}
   {:keys [oid] :as msg}]
  (swap! state-ref update :objects dissoc oid))

(defn obj-forget-all
  [{:keys [state-ref] :as svc}
   msg]
  (swap! state-ref assoc :objects {}))

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
             :obj-request #(obj-request svc %)
             :obj-forget #(obj-forget svc %)
             :obj-forget-all #(obj-forget-all svc %)}
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