(ns shadow.cljs.ui.components.inspect-cards
  (:require
    [shadow.experiments.grove :as sg :refer (<<)]
    [shadow.experiments.grove.cards.env :as gce]
    [shadow.experiments.grove.db :as db]
    [shadow.cljs.ui.components.inspect :as inspect]
    [shadow.cljs.ui.schema :refer (schema)]
    [shadow.cljs.model :as m]))

(gce/register-card ::inspect-as-edn
  (let [o1 (db/make-ident ::m/object 1)]

    {:schema schema
     :db
     {::m/inspect
      {:nav-stack []
       :display-type :edn
       :object o1}
      o1
      {:db/ident o1
       :oid 1
       ::m/object-as-edn "[:some, edn, \"value\"]"
       :summary {:obj-type "cljs.core/DemoType"}}}})

  (<< [:div.flex.flex-col.h-full
       (inspect/ui-inspect)]))

(gce/register-card ::inspect-as-pprint
  (let [o1 (db/make-ident ::m/object 1)]

    {:schema schema
     :db
     {::m/inspect
      {:nav-stack []
       :display-type :pprint
       :object o1}
      o1
      {:db/ident o1
       :oid 1
       ::m/object-as-pprint "imagine something pprinted"
       :summary {:obj-type "cljs.core/DemoType"}}}})

  (<< [:div.flex.flex-col.h-full
       (inspect/ui-inspect)]))

(gce/register-card ::inspect-as-browse
  (let [o1 (db/make-ident ::m/object 1)]
    {:schema schema
     :db
     {::m/inspect
      {:nav-stack []
       :display-type :browse
       :object o1}
      o1
      {:db/ident o1
       :oid 1
       :fragment-vlist
       {:item-count 0
        :offset 0
        :slice
        {0 {:key [false "key0"] :val [false "val0"]}
         1 {:key [false "key1"] :val [false "val1"]}
         2 {:key [false "key2"] :val [false "val2"]}}}
       :summary
       {:obj-type "cljs.core/DemoType"
        :data-type :map
        :entries 3}}}})

  (<< [:div.flex.flex-col.h-full
       (inspect/ui-inspect)]))