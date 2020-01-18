(ns shadow.cljs.ui.env
  (:require
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.model :as m]))

(def schema
  {::m/runtime
   {:type :entity
    :attrs {:rid [:primary-key number?]}}

   ::m/object
   {:type :entity
    :attrs {:oid [:primary-key number?]
            ::m/runtime [:one ::m/runtime]}}

   ::m/http-server
   {:type :entity
    :attrs {::m/http-server-id [:primary-key number?]}}

   ::m/build
   {:type :entity
    :attrs {::m/build-id [:primary-key keyword?]}}})


(defonce data-ref
  (-> {::m/current-page :db/loading
       ::m/builds :db/loading
       ::m/http-servers :db/loading
       ::m/init-complete :db/loading ;; used a marker for initial suspense
       ::m/api-ws-connected false
       ::m/tool-ws-connected false
       ::m/runtimes []
       ::m/active-builds []}
      (db/configure schema)
      (atom)))

(defonce app-ref
  (-> {}
      (sw/prepare data-ref)))