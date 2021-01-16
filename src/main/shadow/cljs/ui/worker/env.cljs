(ns shadow.cljs.ui.worker.env
  (:require
    [shadow.experiments.grove.runtime :as gr]
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.ui.schema :refer (schema)]
    [shadow.cljs.model :as m]))

(defonce data-ref
  (-> {::m/current-page :db/loading
       ::m/builds :db/loading
       ::m/http-servers :db/loading
       ::m/init-complete? :db/loading ;; used a marker for initial suspense
       ::m/relay-ws-connected false
       ::m/ui-options {} ;; FIXME: should probably store this somewhere on the client side too
       ::m/runtimes []
       ::m/active-builds []
       ::m/tap-latest nil
       ::m/inspect
       {:current 0
        :stack
        [{:type :tap-panel}]}}
      (db/configure schema)
      (atom)))

(defonce app-ref
  (-> {}
      (gr/prepare data-ref ::db)))