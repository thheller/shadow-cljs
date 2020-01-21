(ns shadow.cljs.ui.worker.builds
  (:require
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.model :as m]))


(defmethod db/query-calc ::m/active-builds
  [env db _ query-part params]
  (->> (db/all-of db ::m/build)
       (filter ::m/build-worker-active)
       (sort-by ::m/build-id)
       (map :db/ident)
       (into [])))
