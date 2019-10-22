(ns shadow.cljs.ui.pages.loading
  (:require
    [com.fulcrologic.fulcro.components :as fc :refer (defsc)]
    [shadow.markup.react :as html :refer (defstyled)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.ui.style :as s]
    ))

(defsc Page [this props]
  {:ident
   (fn []
     [::ui-model/page-loading 1])

   :query
   (fn []
     [])

   :initial-state
   (fn [p]
     {})}

  (s/main-contents
    (html/div {:id "page-loading"} "Loading ...")))

(def ui-page (fc/factory Page {}))

