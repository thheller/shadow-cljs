(ns shadow.cljs.ui.pages.dashboard
  (:require
    [fulcro.client.primitives :as fp :refer (defsc)]
    [shadow.markup.react :as html]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.util :as util]))

(defsc BuildPanel [this props]
  {:ident
   (fn []
     [::m/build-id (::m/build-id props)])

   :query
   (fn []
     [::m/build-id
      ::m/build-worker-active
      ::m/build-status])}

  (js/console.log ::build-panel props)
  (html/div
    (html/h2 (name (::m/build-id props)))

    (util/dump (::m/build-status props))
    ))

(def ui-build-panel (fp/factory BuildPanel {:keyfn ::m/build-id}))

(defsc Page [this props]
  {:ident
   (fn []
     [:PAGE/dashboard 1])

   :query
   (fn []
     [{::ui-model/active-builds (fp/get-query BuildPanel)}])

   :initial-state
   (fn [p]
     {:PAGE/dashboard 1
      :PAGE/id 1
      ::ui-model/active-builds []})}

  (s/main-contents
    (html/div "dashboard")

    (html/for [build (::ui-model/active-builds props)]
      (ui-build-panel build))))

(def ui-page (fp/factory Page {}))

