(ns shadow.cljs.ui.worker.env
  (:require
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.ui.schema :refer (schema)]
    [shadow.cljs.model :as m]))

(defonce data-ref
  (-> {::m/current-page :db/loading
       ::m/builds :db/loading
       ::m/http-servers :db/loading
       ::m/init-complete? :db/loading ;; used a marker for initial suspense
       ::m/relay-ws-connected false
       ::m/runtimes []
       ::m/active-builds []}
      (db/configure schema)
      (atom)))

(defonce app-ref
  (-> {}
      (sw/prepare data-ref ::db)))