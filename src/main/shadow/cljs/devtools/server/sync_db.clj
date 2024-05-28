(ns shadow.cljs.devtools.server.sync-db
  (:require
    [shadow.cljs :as-alias m]))

;; first attempt at keeping some state in sync with the UI
;; for now just a dumb atom


(def NOT-FOUND (Object.))

;; collect all changes made to a table entry key
;; so that we can properly record keys that are added, removed and updated
;; so the other side can properly assoc/dissoc them
;; this is a very dumb and naive diff mechanism, but good enough for now
(defn table-entry-diff [acc table entry-id oent nent]
  (reduce
    (fn [acc key]
      (let [oval (get oent key NOT-FOUND)
            nval (get nent key NOT-FOUND)]

        (cond
          (= oval nval)
          acc

          (identical? oval NOT-FOUND)
          (conj acc [:entity-add table entry-id key nval])

          (identical? nval NOT-FOUND)
          (conj acc [:entity-remove table entry-id key])

          :else
          (conj acc [:entity-update table entry-id key nval]))))
    acc
    (-> #{} (into (keys oent)) (into (keys nent)))))

(defn table-diff [acc table-id otable ntable]
  (let [all-keys
        (-> #{}
            (into (keys otable))
            (into (keys ntable)))]

    (reduce
      (fn [acc key]
        (let [oval (get otable key NOT-FOUND)
              nval (get ntable key NOT-FOUND)]
          (cond
            (= oval nval)
            acc

            (identical? oval NOT-FOUND)
            (conj acc [:table-add table-id key nval])

            (identical? nval NOT-FOUND)
            (conj acc [:table-remove table-id key])

            :else
            (table-entry-diff acc table-id key oval nval)
            )))
      acc
      all-keys)))

(defn db-diff [old-db new-db]
  (reduce
    (fn [acc table]
      (let [otable (get old-db table)
            ntable (get new-db table)]
        ;; bypass unchanged tables, assuming that = will never happen if not identical?
        (if (identical? otable ntable)
          acc
          (table-diff acc table otable ntable))))
    []
    (keys old-db)))

(comment
  (table-entry-diff [] :table :entry
    {:id 1 :foo "bar"}
    {:id 1 :foo "baz" :x 1})

  (table-diff [] :foo
    {1 {:id 1 :foo "bar"}
     3 {:id 3}}
    {1 {:id 1 :foo "baa" :y 1}
     2 {:id 1 :foo "bar"}}
    ))


(defn update! [sync-db update-fn & args]
  (swap! sync-db
    (fn [db]
      (apply update-fn db args))))

(defn start []
  (atom
    {::m/build {}
     ::m/repl-history {}
     ::m/repl-stream
     {:default
      {:stream-id :default
       :target 1
       :target-op :clj-eval
       :target-ns 'shadow.user}}}))

(defn stop [db])
