(ns shadow.cljs.ui.pages.dashboard
  (:require
    [fulcro.client.primitives :as fp :refer (defsc)]
    [shadow.markup.react :as html :refer (defstyled)]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.transactions :as tx]
    [shadow.cljs.ui.components.build-status :as build-status]))

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

    (html/tr
      (html/td (name (::m/http-server-id props)))
      (html/td (html/a {:href url :target "_blank"} url)))
    ))

(def ui-http-server (fp/factory HttpServer {:keyfn ::m/http-server-id}))

(defstyled build-panel-container :div
  [env]
  {:padding [0 0 20 0]})

(defstyled build-panel-toolbar :div
  [env]
  {:padding [0 0 10 0]})

(defstyled build-panel-label :div
  [env]
  {:display "inline-block"
   :font-weight "bold"
   :min-width 100
   :padding-right 20})

(defsc BuildPanel [this props]
  {:ident
   (fn []
     [::m/build-id (::m/build-id props)])

   :query
   (fn []
     [::m/build-id
      ::m/build-worker-active
      ::m/build-http-server
      ::m/build-status])}

  (let [{::m/keys [build-id build-status]} props]
    (build-panel-container
      (build-panel-toolbar
        (build-panel-label (html/a {:href (str "/builds/" (name build-id))} (name build-id))))

      (build-status/render-build-status build-status))))

(def ui-build-panel (fp/factory BuildPanel {:keyfn ::m/build-id}))

(defsc Page [this props]
  {:ident
   (fn []
     [::ui-model/page-dashboard 1])

   :query
   (fn []
     [{::ui-model/http-servers (fp/get-query HttpServer)}
      {::ui-model/active-builds (fp/get-query BuildPanel)}])

   :initial-state
   (fn [p]
     {::ui-model/http-servers []
      ::ui-model/active-builds []})}

  (s/main-contents
    (s/page-title "Active HTTP Servers")

    (html/table
      (html/tbody
        (html/for [server (::ui-model/http-servers props)]
          (ui-http-server server))))

    (s/page-title "Active Builds")
    (html/for [build (::ui-model/active-builds props)]
      (ui-build-panel build))))

(def ui-page (fp/factory Page {}))

