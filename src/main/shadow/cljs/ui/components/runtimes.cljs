(ns shadow.cljs.ui.components.runtimes
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.inspect :as inspect]
    [clojure.string :as str]
    [goog.date.relative :as rel]))

(defn age-display [since]
  (rel/format since))

(defc ui-runtime-overview [ident]
  [{:keys [rid runtime-info supported-ops] :as data}
   (sg/query-ident ident
     [:rid
      :runtime-info
      :supported-ops])]

  (let [{:keys [lang type since user-agent desc]} runtime-info]

    (<< [:tr.align-top
         [:td.pl-2.text-right rid]
         [:td.pl-2.whitespace-no-wrap (name type)]
         [:td.pl-2.whitespace-no-wrap (if-not lang "-" (name lang))]
         [:td.pl-2.whitespace-no-wrap (age-display since)]
         [:td.pl-2 (or desc user-agent "")]]
        [:tr
         [:td.border-b.py-2 {:colSpan 5}
          #_(when (contains? supported-ops :eval-cljs)
              (<< [:a
                   {:class inspect/css-button
                    :href (str "/runtime/" rid "/eval-cljs")}
                   "cljs eval"]))
          #_(when (contains? supported-ops :eval-clj)
              (<< [:a
                   {:class inspect/css-button
                    :href (str "/runtime/" rid "/repl")}
                   "clj eval"]))
          (when (contains? supported-ops :db/get-databases)
            (<< [:a
                 {:class inspect/css-button
                  :href (str "/runtime/" rid "/db-explorer")}
                 "db explorer"]))]])))

(defn ui-runtime-listing [runtimes]
  (<< [:table.w-full
       [:thead
        [:tr
         [:th.border-b.py-1.pl-2.text-sm.font-bold.text-right "ID"]
         [:th.border-b.py-1.pl-2.font-bold.text-left "Type"]
         [:th.border-b.py-1.pl-2.font-bold.text-left "Lang"]
         [:th.border-b.py-1.pl-2.font-bold.text-left "Since"]
         [:th.border-b.py-1.pl-2.font-bold.text-left "Desc"]]]
       [:tbody
        (sg/render-seq runtimes identity ui-runtime-overview)]]))

(defc ui-page []
  [{::m/keys [runtimes-sorted]}
   (sg/query-root
     [::m/runtimes-sorted])]

  (<< [:div.flex-1.overflow-auto
       [:div.p-2.font-bold.border-b "Available Runtimes"]
       (ui-runtime-listing runtimes-sorted)]))
