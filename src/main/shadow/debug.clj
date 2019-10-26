(ns shadow.debug)

(defn merge-opts [m opts]
  (cond
    (map? opts)
    (merge m opts)

    (keyword? opts)
    (assoc m :label opts)

    :else
    (assoc m :view-opts opts)))

(defn dbg-info [env form opts]
  (let [{:keys [line column]} (meta form)]
    (-> {:ns (str *ns*)}
        (cond->
          line
          (assoc :line line)
          column
          (assoc :column column))
        (merge-opts opts))))

;; abusing tap> because it is in core and doesn't require additional requires
;; which is fine for CLJ but problematic for CLJS

;; FIXME: make this all noop unless enabled

(defmacro ?>
  ([obj]
   (?> obj {}))
  ([obj opts]
   `(tap> [:shadow.remote/wrap ~obj ~(dbg-info &env &form opts)])))

(defn tap-> [obj opts]
  (tap> [:shadow.remote/wrap obj opts])
  obj)

(defmacro ?->
  ([obj]
   (?-> obj {}))
  ([obj opts]
   `(tap-> ~obj ~(dbg-info &env &form opts))))

(defmacro ?->>
  ([obj]
   (?->> {} obj))
  ([opts obj]
   `(tap-> ~obj ~(dbg-info &env &form opts))))

(comment
  (?> (:clj-runtime (shadow.cljs.devtools.server.runtime/get-instance))
    :view-opts
    )

  (-> :thing
      (?-> :view-opts))

  (?> :hello ::send-help)

  (-> :x
      (?-> ::view-opts))

  (-> :thing
      (?-> {:label "you fool"}))


  (->> :thing
       (?->> ::omg)))
