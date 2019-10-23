(ns shadow.cljs.ui.pages.dashboard
  (:require
    [com.fulcrologic.fulcro.components :as fc :refer (defsc)]
    [shadow.markup.react :as html :refer (defstyled)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.transactions :as tx]
    [shadow.cljs.ui.components.build-panel :as build-panel]))

(defsc HttpServer [this props]
  {:ident
   (fn []
     [::m/http-server-id (::m/http-server-id props)])

   :query
   (fn []
     [::m/http-server-id
      ::m/http-url
      ::m/https-url])}

  (let [url (or (::m/https-url props)
                (::m/http-url props))]

    (html/div {:className "text-l pl-8"}
      (html/a {:href url :target "_blank"}
        url
        ))))

(def ui-http-server (fc/factory HttpServer {:keyfn ::m/http-server-id}))

(defsc Page [this props]
  {:ident
   (fn []
     [::ui-model/page-dashboard 1])

   :query
   (fn []
     [{::ui-model/http-servers (fc/get-query HttpServer)}
      {::ui-model/active-builds (fc/get-query build-panel/BuildPanel)}])

   :initial-state
   (fn [p]
     {::ui-model/http-servers []
      ::ui-model/active-builds []})}

  (s/main-contents
    (s/cards-title "Active HTTP Servers")

    (html/for [server (::ui-model/http-servers props)]
      (ui-http-server server))

    (s/cards-title "Active Builds")
    (html/for [build (::ui-model/active-builds props)]
      (build-panel/ui-build-panel build))))

(def ui-page (fc/factory Page {}))

