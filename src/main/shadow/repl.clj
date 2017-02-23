(ns shadow.repl)

(defonce levels-ref (volatile! (list)))

(def ^:dynamic *features* {})

(defn levels []
  @levels-ref)

(defn push-level [level]
  (let [level-id
        (count @levels-ref)

        level
        (assoc level
          ::level-id level-id
          ::level-ns (str *ns*))]

    (vswap! levels-ref conj level)

    level
    ))

(defmacro takeover [level & body]
  `(let [level# (push-level ~level)]
     (try
       (when-let [fn# (get *features* ::level-enter)]
         (fn# level#))

       ~@body

       (finally
         (when-let [fn# (get *features* ::level-exit)]
           (fn# level#))
         (vswap! levels-ref pop)))))

(defn get-feature [id]
  (get *features* id))

(defn has-feature? [id]
  (contains? *features* id))

(defmacro with-features [new-features & body]
  `(binding [*features* (merge *features* ~new-features)]
     ~@body
     ))
