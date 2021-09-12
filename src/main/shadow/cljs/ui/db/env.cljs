(ns shadow.cljs.ui.db.env
  (:require
    [shadow.experiments.grove.runtime :as gr]
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.model :as m]))

(def schema
  {::m/runtime
   {:type :entity
    :primary-key :runtime-id
    :attrs {}}

   ::m/error
   {:type :entity
    :primary-key :error-id
    :attrs {}}

   ::m/object
   {:type :entity
    :primary-key :oid
    :attrs {}
    :joins {::m/runtime [:one ::m/runtime]}}

   ::m/http-server
   {:type :entity
    :primary-key ::m/http-server-id
    :attrs {}}

   ::m/build
   {:type :entity
    :primary-key ::m/build-id
    :attrs {}}

   ;; FIXME: this should have its own namespace for db-explorer
   ::m/database
   {:type :entity
    :primary-key :db-id
    :attrs {}
    :joins {::m/runtime [:one ::m/runtime]}}

   ::m/runtime-ns
   {:type :entity
    :primary-key [::m/runtime :ns]
    :attrs {}
    :joins {::m/runtime [:one ::m/runtime]}}

   ::m/runtime-var
   {:type :entity
    :primary-key [::m/runtime :var]
    :attrs {}
    :joins {::m/runtime [:one ::m/runtime]
            ::m/runtime-ns [:one ::m/runtime-ns]}}
   })



(defonce data-ref
  (-> {::m/current-page :db/loading
       ::m/builds :db/loading
       ::m/http-servers :db/loading
       ::m/init-complete? :db/loading ;; used a marker for initial suspense
       ;; assume that the first connect will succeed
       ;; otherwise shows disconnect banner for a few ms on startup
       ::m/relay-ws-connected true
       ::m/ui-options {} ;; FIXME: should probably store this somewhere on the client side too
       ::m/runtimes []
       ::m/active-builds []
       ::m/tap-stream (list)
       ::m/tap-latest nil
       ::m/inspect
       {:current 0
        :stack
        [{:type :tap-panel}]}}
      (db/configure schema)
      (atom)))

(defonce rt-ref
  (-> {}
      (gr/prepare data-ref ::db)))