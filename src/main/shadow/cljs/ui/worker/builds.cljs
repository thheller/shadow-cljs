(ns shadow.cljs.ui.worker.builds
  (:require
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.eql-query :as eql]
    [shadow.cljs.model :as m]))

(defmethod eql/attr ::m/active-builds
  [env db _ query-part params]
  (->> (db/all-of db ::m/build)
       (filter ::m/build-worker-active)
       (sort-by ::m/build-id)
       (map :db/ident)
       (into [])))


(defmethod eql/attr ::m/build-sources-sorted
  [env db current query-part params]
  (when-let [info (get-in current [::m/build-status :info])]
    (let [{:keys [sources]} info]
      (->> sources
           (sort-by :resource-name)
           (vec)
           ))))