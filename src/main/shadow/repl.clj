(ns shadow.repl)

(defonce root-id-ref
  (volatile! 0))

(defn next-root-id []
  (vswap! root-id-ref inc))

(def ^:dynamic *root-id* (next-root-id))

(defonce roots-ref
  (volatile! {}))

(defonce features-ref
  (volatile! {}))

(defn get-feature [id]
  (get @features-ref id))

(defn has-feature? [id]
  (contains? @features-ref id))

(defn roots []
  @roots-ref)

(defn push-level [level]
  (let [level-id
        (-> @roots-ref (get *root-id*) ::levels count)

        level
        (assoc level
          ::root-id *root-id*
          ::level-id level-id
          ::level-ns (str *ns*))]

    (vswap! roots-ref update-in [*root-id* ::levels] conj level)

    level
    ))

(defn do-level-enter! [level]
  (let [level (push-level level)]
    (when-let [fn (get-feature ::level-enter)]
      (fn level))
    level))

(defn do-level-exit! [level]
  (when-let [fn (get-feature ::level-exit)]
    (fn level))
  (vswap! roots-ref update-in [*root-id* ::levels] pop))

(defmacro takeover [level & body]
  `(let [level# (do-level-enter! ~level)]
     (try
       ~@body
       (finally
         (do-level-exit! level#)
         ))))

(defn provide! [features]
  (vswap! features-ref merge features)
  ::provided)

(defn clear! []
  (vreset! features-ref {}))

(defmacro enter-root [root-info & body]
  {:pre [(map? root-info)]}
  `(binding [*root-id* (next-root-id)]
     (vswap! roots-ref assoc *root-id* (assoc ~root-info ::levels (list)))
     ~@body))