(ns shadow.cljs.ui.components.db-explorer
  (:require
    [fipp.edn :refer (pprint)]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.inspect :as inspect]
    [shadow.experiments.arborist.attributes :as a]
    [shadow.experiments.grove.ui.vlist :as vlist]
    [shadow.experiments.grove.ui.forms :as form]
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.grove.protocols :as gp]))

(def rows-vlist
  (vlist/configure ::m/table-rows-vlist
    {:item-height 31}
    (fn [entry idx opts]
      (<< [:div.truncate.bg-white.border-b-2.font-mono.p-1.cursor-pointer
           {:on-click [::select-row! entry idx]}
           (pr-str entry)]))))


(def ui-database-form
  (form/configure
    {:fields
     {:table {:type :keyword}
      :row {:type :any}}
     :eager-submit true
     :submit
     (fn [env data]
       (sg/run-tx env [::m/table-query-update! data])
       ;; (js/console.log "database form submit" env data)
       )}))

(defc ui-database [db-ident]
  [{::m/keys [tables table-query table-entry] :as data}
   (sg/query-ident db-ident
     [::m/tables
      ::m/table-query
      ::m/table-entry])

   table-options
   (->> tables
        (sort)
        (map (fn [item]
               [item (str item)]))
        (into [[nil "Select Table ..."]]))

   form
   (ui-database-form (assoc table-query :db-ident db-ident))

   ::select-row!
   (fn [env e val]
     ;; protocolize/helper fn
     (.field-did-change! ^Form form :row val))]

  (<< [:div.flex.flex-col.h-full
       [:div.p-2.flex
        [:div.p-1.pr-2.w-20 "Tables:"]
        (form/select form :table table-options)]

       (if-not (:table table-query)
         (<< [:div "No Table selected."])
         (<< [:div.flex-1.flex.border-t-2.overflow-hidden
              [:div.overflow-hidden {:class "w-1/3 overflow-hidden"}
               (rows-vlist {:ident db-ident})]
              [:div.flex-1.border-l-2
               ;; FIXME: don't use pprint, create proper dom structure like inspect
               ;; not using inspect because we have the full value and I eventually want to be able to
               ;; edit and update it from here
               [:textarea.w-full.h-full.font-mono.p-4.whitespace-no-wrap
                (with-out-str
                  (pprint table-entry))]]]))]))

(def ui-page-form
  (form/configure
    {:fields
     {:db {:type :keyword}}}))

(defc ui-page [runtime-ident]
  [{::m/keys [databases selected-database] :as data}
   (sg/query-ident runtime-ident
     [{::m/databases
       [:db/ident
        :db-id]}
      ;; FIXME: should check runtime availability
      ;; no interaction possible if runtime is gone
      ::m/selected-database])

   db-options
   (->> databases
        (map (fn [item]
               [(:db/ident item) (str (:db-id item))]))
        (into [[nil "Select Database ..."]]))

   ;; FIXME: pass in db-options here or for each field?
   ;; may skip render if done here?
   form
   (ui-page-form {:db selected-database})]

  (<< [:div.flex-1.flex.flex-col.overflow-hidden
       [:div.p-2.flex
        [:div.p-1.pr-2.w-20 "Database:"]
        ;; FIXME: where to pass in styles/maybe extra dom-attrs?
        (form/select form :db db-options)]

       [:div.flex-1.overflow-hidden
        (if-not selected-database
          (<< [:div.p-2 "No database selected."])
          (ui-database selected-database))]]))
