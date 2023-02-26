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

  (let [ts
        (now)

        entry
        {:obj obj
         :data (d/datafy obj)
         :obj-info obj-info
         :added-at ts
         :access-at ts ;; tracking that for GC purposes
         :oid oid}]

    (assoc-in state [:objects oid] entry)))

(declare register)

(defn obj-type-string [obj]
  (if (nil? obj)
    "nil"
    #?(:clj
       (str (when (fn? obj) "function: ") (-> (class obj) (.getName)))
       :cljs
       (pr-str (type obj)))))


(defn get-data-type [summary {:keys [data] :as entry}]
  (try
    (cond
      (nil? data)
      (assoc summary :data-type :nil)

      (string? data)
      (assoc summary :data-type :string :data-count (count data))

      (boolean? data)
      (assoc summary :data-type :boolean)

      (number? data)
      (assoc summary :data-type :number)

      (keyword? data)
      (assoc summary :data-type :keyword)

      (symbol? data)
      (assoc summary :data-type :symbol)

      (map? data)
      (assoc summary :data-type :map :data-count (count data))

      (vector? data)
      (assoc summary :data-type :vec :data-count (count data))

      (set? data)
      (assoc summary :data-type :set :data-count (count data))

      (list? data)
      (assoc summary :data-type :list :data-count (count data))

      ;; lazy seqs
      (seq? data)
      (assoc summary :data-type :seq)

      :else
      (assoc summary :data-type :unsupported))

    (catch #?(:cljs :default :clj Exception) e
      ;; just in case any of the above fail, leaving marker for debugging later
      ;; intentionally dropping exception, since handling it breaks flow
      #?(:clj (shadow.jvm-log/warn-ex e ::get-data-type-fail))

      (assoc summary :data-type :unsupported :data-type-fail true))))

(defn merge-source-info [summary {:keys [obj-info]}]
  (merge summary (select-keys obj-info [:ns :line :column :label])))

(defn inspect-entry!
  [{:keys [state-ref] :as this}
   {:keys [obj data added-at] :as entry}]

  (-> {:added-at added-at
       :datafied (not (identical? data obj))
       :obj-type (obj-type-string obj)
       :supports #{}}
      (get-data-type entry)
      (merge-source-info entry)
      (as-> $
        (reduce
          (fn [summary inspect-fn]
            (inspect-fn summary entry))
          $
          (:inspectors @state-ref)))))

(defn obj-describe*
  [{:keys [state-ref] :as this}
   oid]
  (when-some [entry (get-in @state-ref [:objects oid])]
    (swap! state-ref assoc-in [:objects oid :access-at] (now))
    (inspect-entry! this entry)))

(defn handler-with-object
  [handler-fn]
  (fn [{:keys [state-ref runtime] :as this}
       {:keys [op oid] :as msg}]

    (let [entry (get-in @state-ref [:objects oid])]
      (if-not entry
        (shared/reply runtime msg {:op :obj-not-found :oid oid})

        (try
          (swap! state-ref assoc-in [:objects oid :access-at] (now))

          (let [result (handler-fn this entry msg)]

            ;; FIXME: add support for generic async results
            ;; all handlers should already be sync but allow async results
            (if-not (obj-ref? result)
              (shared/reply runtime msg
                {:op :obj-result
                 :oid oid
                 :result result})

              (let [new-oid
                    (register this (:obj result) {})

                    reply-msg
                    (-> {:op :obj-result-ref
                         :oid oid
                         :ref-oid new-oid}
                        (cond->
                          ;; only send new-obj :summary when requested
                          (:summary msg)
                          (assoc :summary (obj-describe* this new-oid))))]
                (shared/reply runtime msg reply-msg))))

          (catch #?(:clj Exception :cljs :default) e
            #?(:cljs (js/console.warn "action-request-action failed" (:obj entry) e)
               :clj (shadow.jvm-log/warn-ex e ::obj-request-failed msg))
            (shared/reply runtime msg
              {:op :obj-request-failed
               :oid oid
               :msg msg
               :ex-oid (register this e {:msg msg})})))))))

(def obj-get-value
  (handler-with-object
    (fn [this {:keys [obj] :as entry} msg]
      obj)))

;; 1meg?
(def default-max-print-size (* 1 1024 1024))

(def obj-edn
  (handler-with-object
    (fn [this {:keys [data] :as entry} {:keys [limit] :or {limit default-max-print-size} :as msg}]
      (let [lw (lw/limit-writer limit)]
        #?(:clj
           (print-method data lw)
           :cljs
           (pr-writer data lw (pr-opts)))
        (lw/get-string lw)))))

(def obj-pprint
  (handler-with-object
    (fn [this {:keys [data] :as entry} {:keys [limit] :or {limit default-max-print-size} :as msg}]
      ;; CLJ pprint for some reason doesn't run out of memory when printing circular stuff
      ;; but it never finishes either
      (let [lw (lw/limit-writer limit)]
        (pprint data lw)
        (lw/get-string lw)))))

(def obj-edn-limit
  (handler-with-object
    (fn [this {:keys [data] :as entry} {:keys [limit] :as msg}]
      (lw/pr-str-limit data limit))))

(def obj-str
  (handler-with-object
    (fn [this {:keys [obj] :as entry} msg]
      (str obj)
      )))

(def obj-ex-str
  (handler-with-object
    (fn [this {ex :obj :as entry} msg]
      #?(:cljs
         (if (instance? js/Error ex)
           (error->str ex)
           (str "Execution error:\n"
                ;; can be any object, really no hope in making this any kind of readable
                ;; capping it so throwing something large doesn't blow up the REPL
                "  " (second (lw/pr-str-limit ex 200)) "\n"
                "\n"))

         :clj
         (error-format ex)))))

(defn exception? [x]
  #?(:clj (instance? java.lang.Throwable x)
     ;; everything can be thrown in JS
     ;; (throw "x")
     ;; (throw (js/Promise.resolved "x"))
     :cljs true ;; (instance? js/Error x)
     ))

(defn simple-value? [val]
  ;; anything that serializes to less than 32 bytes (ref-id is md5 hex string)
  ;; should just be sent as is, bypassing all the ref logic
  (or (nil? val)
      (boolean? val)
      (number? val)
      (keyword? val)
      ;; symbols only without meta
      (and (symbol? val) (nil? (meta val)))
      ;; small strings only
      (and (string? val) (> 64 (count val)))
      ;; empty cols with no meta
      (and (coll? val) (empty? val) (nil? (meta val)))))

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

(defn attempt-to-sort [coll]
  (vec
    (try
      (sort smart-comp coll)
      (catch #?(:clj Exception :cljs :default) e
        coll))))

(defn cache-view-order [state-ref {:keys [oid view-order]} coll]
  (or view-order
      (let [view-order (attempt-to-sort coll)]
        (swap! state-ref assoc-in [:objects oid :view-order] view-order)
        view-order
        )))

(def obj-nav
  (handler-with-object
    (fn [{:keys [state-ref]} {:keys [data] :as entry} {:keys [idx] :as msg}]
      (cond
        (or (vector? data) (list? data))
        (let [val (nth data idx)
              nav (d/nav data idx val)]
          (obj-ref nav))

        (map? data)
        (let [view-order (cache-view-order state-ref entry (keys data))
              key (nth view-order idx)
              val (get data key)
              nav (d/nav data key val)]
          (obj-ref nav))

        (set? data)
        (let [view-order (cache-view-order state-ref entry data)
              val (nth view-order idx)
              nav (d/nav data idx val)]
          (obj-ref nav))

        :else
        (throw (ex-info "nav not supported?" entry))))))

(def obj-fragment
  (handler-with-object
    (fn
      [{:keys [state-ref]}
       {:keys [data] :as entry}
       {:keys [start num val-limit]
        :or {val-limit 100}
        :as msg}]
      (cond
        (map? data)
        (let [{:keys [key-limit] :or {key-limit 100}} msg
              view-order (cache-view-order state-ref entry (keys data))
              end (min (count view-order) (+ start num))
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

          fragment)

        (vector? data)
        (let [end (min (count data) (+ start num))
              idxs (range start end)
              fragment
              (reduce
                (fn [m idx]
                  (let [val (nth data idx)]
                    (assoc m idx {:val (lw/pr-str-limit val val-limit)})))
                {}
                idxs)]
          fragment)

        (list? data)
        (let [end (min (count data) (+ start num))
              idxs (range start end)
              fragment
              (reduce
                (fn [m idx]
                  (let [val (nth data idx)]
                    (assoc m idx {:val (lw/pr-str-limit val val-limit)})))
                {}
                idxs)]

          fragment)

        (set? data)
        (let [view-order (cache-view-order state-ref entry data)
              end (min (count view-order) (+ start num))
              idxs (range start end)
              fragment
              (reduce
                (fn [m idx]
                  (let [val (nth view-order idx)]
                    (assoc m idx {:val (lw/pr-str-limit val val-limit)})))
                {}
                idxs)]

          fragment)))))

;; keeping this for backwards compatibility, found at least two libs using it
;; https://github.com/eerohele/Tutkain/blob/34b1ae9147a28faa9badedf3818f69bbb9e0e4ef/clojure/src/tutkain/shadow.clj#L234
;; https://github.com/mauricioszabo/repl-tooling/blob/b4962dd39b84d60cbd087a96ba6fccb1bffd0bd6/src/repl_tooling/repl_client/shadow_ws.cljs

(defn obj-request [this {:keys [request-op] :as msg}]
  (let [real-handler
        (case request-op
          :str obj-str
          :ex-str obj-ex-str
          :edn obj-edn
          :edn-limit obj-edn
          :pprint this
          :nav this
          :fragment obj-fragment)]
    (real-handler this msg)))

(comment
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

  (def x (pageable-seq {:data (map (fn [x] (prn [:realize x]) x) (range 10))}))

  (let [chunk (get-in x [:handlers :chunk])]
    (chunk {:start 0 :num 5})
    )

  (let [chunk (get-in x [:handlers :chunk])]
    (chunk {:start 5 :num 10})
    ))

(defn obj-describe
  [{:keys [runtime] :as this}
   {:keys [oid] :as msg}]
  (if-let [summary (obj-describe* this oid)]
    (shared/reply runtime msg {:op :obj-summary :oid oid :summary summary})
    (shared/reply runtime msg {:op :obj-not-found :oid oid})))

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

(defn add-inspector
  [{:keys [state-ref] :as this} inspect-fn]
  {:pre [(fn? inspect-fn)]}
  (swap! state-ref update :inspectors conj inspect-fn)
  this)

(defn start [runtime]
  (let [state-ref
        (atom {:objects {}
               :inspectors #{}
               :id-seq-ref 0})

        svc
        (-> {:runtime runtime
             :state-ref state-ref}

            (add-inspector
              (fn [summary {:keys [obj] :as entry}]
                (if-not (simple-value? obj)
                  summary
                  (update summary :supports conj :obj-get-value))))

            (add-inspector
              (fn [summary entry]
                (update summary :supports conj :obj-str)))

            (add-inspector
              (fn [summary {:keys [obj] :as entry}]
                (if (exception? obj)
                  (update summary :supports conj :obj-ex-str)
                  summary)))

            ;; FIXME: maybe only support these for clojure types?
            (add-inspector
              (fn [summary entry]
                (update summary :supports conj :obj-edn)))

            (add-inspector
              (fn [summary entry]
                (update summary :supports conj :obj-edn-limit)))

            (add-inspector
              (fn [summary {:keys [data] :as entry}]
                (if (or (coll? data) (seq? data))
                  (update summary :supports conj :obj-pprint)
                  summary)))

            (add-inspector
              (fn [summary {:keys [data] :as entry}]
                (if (and (or (map? data) (vector? data) (set? data) (list? data))
                         (seq data))
                  (update summary :supports conj :obj-nav)
                  summary)))

            (add-inspector
              (fn [summary {:keys [data] :as entry}]
                (if (and (or (map? data) (vector? data) (set? data) (list? data))
                         (seq data))
                  (update summary :supports conj :obj-fragment)
                  summary)))
            )]

    (p/add-extension runtime
      ::ext
      {:ops {:obj-describe #(obj-describe svc %)
             :obj-request #(obj-request svc %)
             :obj-edn #(obj-edn svc %)
             :obj-get-value #(obj-get-value svc %)
             :obj-edn-limit #(obj-edn-limit svc %)
             :obj-str #(obj-str svc %)
             :obj-pprint #(obj-pprint svc %)
             :obj-nav #(obj-nav svc %)
             :obj-fragment #(obj-fragment svc %)
             :obj-forget #(obj-forget svc %)
             :obj-forget-all #(obj-forget-all svc %)}
       :on-idle #(swap! state-ref basic-gc!)})

    svc))

(defn get-tap-history [{:keys [state-ref] :as svc} num]
  (->> (:objects @state-ref)
       (vals)
       (filter #(= :tap (get-in % [:obj-info :from])))
       (sort-by :added-at)
       (reverse)
       (take num)
       (map :oid)
       (into [])))

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