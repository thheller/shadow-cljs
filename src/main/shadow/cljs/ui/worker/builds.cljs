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

(defmethod eql/attr ::m/build-warnings-count
  [env db current query-part params]
  (let [{:keys [warnings] :as info} (::m/build-status current)]
    (count warnings)))

(defmethod eql/attr ::m/build-runtimes
  [env db current query-part params]
  (let [build-ident (get current :db/ident)
        {::m/keys [build-id] :as build} (get db build-ident)]

    (->> (db/all-of db ::m/runtime)
         (filter (fn [{:keys [runtime-info]}]
                   (and (= :cljs (:lang runtime-info))
                        (= build-id (:build-id runtime-info)))))
         (mapv :db/ident))))