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
  {:padding 20
   :border-radius 4
   :margin-bottom 10
   :background-color "#fff"
   :box-shadow "0 3px 1px -2px rgba(0,0,0,.2), 0 2px 2px 0 rgba(0,0,0,.14), 0 1px 5px 0 rgba(0,0,0,.12)"})

(defstyled build-panel-toolbar :div
  [env]
  {:padding [0 0 10 0]})

(defstyled build-panel-label :a
  [env]
  {:text-decoration "none"
   :font-weight "bold"
   :font-size "1.2em"
   })

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
        (build-panel-label {:href (str "/builds/" (name build-id))} (name build-id)))

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
    #_ (s/page-title "Active HTTP Servers")

    #_(html/table
        (html/tbody
          (html/for [server (::ui-model/http-servers props)]
            (ui-http-server server))))

    (s/cards-title "Active Builds")
    (html/for [build (::ui-model/active-builds props)]
      (ui-build-panel build))))

(def ui-page (fp/factory Page {}))

