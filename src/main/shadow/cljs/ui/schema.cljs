(ns shadow.cljs.ui.schema
  (:require
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
    :attrs {::m/build-id [:primary-key keyword?]}}

   ;; FIXME: this should have its own namespace for db-explorer
   ::m/database
   {:type :entity
    :attrs {:db-id [:primary-key some?]
            ::m/runtime [:one ::m/runtime]}}
   })
