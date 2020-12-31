(ns shadow.cljs.ui.components.runtimes
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.inspect :as inspect]
    [goog.date.relative :as rel]))

(defn age-display [since]
  (rel/format since))

(defc ui-runtime-overview [ident]
  (bind {:keys [runtime-id runtime-info supported-ops] :as data}
    (sg/query-ident ident
      [:runtime-id
       :runtime-info
       :supported-ops]))

  (render
    (let [{:keys [lang build-id host type since user-agent desc]} runtime-info]

      (<< [:tr.align-top
           [:td.pl-2.text-right runtime-id]
           [:td.pl-2.whitespace-no-wrap (if-not lang "-" (name lang))]
           [:td.pl-2.whitespace-no-wrap
            (when build-id
              (<< [:a {:href (str "/build/" (name build-id))} (name build-id)]))]
           [:td.pl-2.whitespace-no-wrap (when host (name host))]
           [:td.pl-2.whitespace-no-wrap (age-display since)]
           [:td.pl-2.truncate (or desc user-agent "")]]
          [:tr
           [:td.border-b.py-2]
           [:td.border-b.py-2 {:colSpan 5}
            #_(when (contains? supported-ops :cljs-eval)
                (<< [:a
                     {:class inspect/css-button
                      :href (str "/runtime/" rid "/cljs-eval")}
                     "cljs eval"]))
            #_(when (contains? supported-ops :clj-eval)
                (<< [:a
                     {:class inspect/css-button
                      :href (str "/runtime/" rid "/repl")}
                     "clj eval"]))
            (when (contains? supported-ops :db/get-databases)
              (<< [:a
                   {:class inspect/css-button
                    :href (str "/runtime/" runtime-id "/db-explorer")}
                   "db explorer"]))]]))))

(defn ui-runtime-listing [runtimes]
  (<< [:table.w-full
       [:thead
        [:tr
         [:th.border-b.py-1.pl-2.text-sm.font-bold.text-right "ID"]
         [:th.border-b.py-1.pl-2.font-bold.text-left "Lang"]
         [:th.border-b.py-1.pl-2.font-bold.text-left "Build"]
         [:th.border-b.py-1.pl-2.font-bold.text-left "Host"]
         [:th.border-b.py-1.pl-2.font-bold.text-left "Since"]
         [:th.border-b.py-1.pl-2.font-bold.text-left "Desc"]]]
       [:tbody
        (sg/render-seq runtimes identity ui-runtime-overview)]]))

(defc ui-page []
  (bind {::m/keys [runtimes-sorted]}
    (sg/query-root
      [::m/runtimes-sorted]))

  (render
    (<< [:div.flex-1.overflow-auto
         [:div.p-2.font-bold.border-b "Available Runtimes"]
         (ui-runtime-listing runtimes-sorted)])))
