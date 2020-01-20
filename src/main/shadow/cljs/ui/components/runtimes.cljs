(ns shadow.cljs.ui.components.runtimes
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.components.inspect :as inspect]))


(defc ui-runtime-overview [ident]
  [{:keys [rid runtime-info supported-ops] :as data}
   (sg/query-ident ident
     [:rid
      :runtime-info
      :supported-ops])]

  (<< [:div.px-2.py-1.flex
       [:div {:style {:width "50px"}} (str "#" rid)]
       (when (contains? supported-ops :eval-cljs)
         (<< [:a
              {:class inspect/css-button
               :href (str "/runtime/" rid "/eval-cljs")}
              "cljs eval"]))
       (when (contains? supported-ops :eval-clj)
         (<< [:a
              {:class inspect/css-button
               :href (str "/runtime/" rid "/eval-clj")}
              "clj eval"]))
       [:div.flex-1.truncate (:user-agent runtime-info)]]))

(defc ui-page []
  [{::m/keys [cljs-runtimes-sorted clj-runtimes-sorted]}
   (sg/query-root
     [::m/cljs-runtimes-sorted
      ::m/clj-runtimes-sorted])]

  (<< [:div.flex-1.overflow-auto
       [:div.p-2.font-bold.border-b "Available ClojureScript Runtimes"]
       (sg/render-seq cljs-runtimes-sorted identity ui-runtime-overview)
       [:div.p-2.font-bold.border-b "Available Clojure Runtimes"]
       (sg/render-seq clj-runtimes-sorted identity ui-runtime-overview)]))
