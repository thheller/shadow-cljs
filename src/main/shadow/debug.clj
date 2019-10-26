(ns shadow.debug)

(defn as-dbg [env form obj opts]
  (let [ns (str *ns*)
        {:keys [line column]} (meta form)]

    (merge
      {:ns ns
       :line line
       :column column
       :obj obj}
      opts)))

(defmacro ?> [obj opts]
  `(tap> ~(as-dbg &env &form obj opts)))


(comment
  (?> (shadow.cljs.devtools.server.runtime/get-instance) {:opt "yo"}))