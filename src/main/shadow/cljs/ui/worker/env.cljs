(ns shadow.cljs.ui.worker.env
  (:require
    [shadow.experiments.grove.runtime :as gr]
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.model :as m]))

(def schema
  {::m/runtime
   {:type :entity
    :attrs {:runtime-id [:primary-key number?]}}

   ::m/error
   {:type :entity
    :attrs {:error-id [:primary-key any?]
            ;; :text string?
            }}

   ::m/object
   {:type :entity
    :attrs {:oid [:primary-key number?]
            ::m/runtime [:one ::m/runtime]}}

   ::m/http-server
   {:type :entity
    :attrs {::m/http-server-id [:primary-key number?]}}

   ::m/build
   {:type :entity
    :attrs {::m/build-id [:primary-key keyword?]}}

   ;; FIXME: this should have its own namespace for db-explorer
   ::m/database
   {:type :entity
    :attrs {:db-id [:primary-key some?]
            ::m/runtime [:one ::m/runtime]}}
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