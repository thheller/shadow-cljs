(ns shadow.cljs.ui.components.build-panel
  (:require
    [fulcro.client.primitives :as fp :refer (defsc)]
    [shadow.markup.react :as html :refer (defstyled)]
    [shadow.cljs.ui.components.build-status :as build-status]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.model :as ui-model]
    [shadow.cljs.ui.style :as s]
    [shadow.cljs.ui.transactions :as tx]))

(defstyled build-panel-container :div
  [env]
  {:border-radius 4
   :margin-bottom 10
   :background-color "#fff"
   :box-shadow "0 3px 1px -2px rgba(0,0,0,.2), 0 2px 2px 0 rgba(0,0,0,.14), 0 1px 5px 0 rgba(0,0,0,.12)"})

(defstyled build-panel-header :div
  [env]
  {:padding [15 15 0 15]})

(defstyled build-panel-label :a
  [env]
  {:text-decoration "none"
   :font-weight "bold"
   :font-size "1.2em"
   })

(defstyled build-panel-status :div
  [env]
  {:padding [15 15]})

(defstyled build-panel-toolbar :div
  [env]
  {:padding [10 15 15 15]})

(defstyled build-panel-action :button
  [env]
  {:display "inline-block"
   :margin-right 10})

(defsc BuildPanel [this props]
  {:ident
   (fn []
     [::m/build-id (::m/build-id props)])

   :query
   (fn []
     [::m/build-id
      ::m/build-worker-active
      ::m/build-http-server
      ::m/build-worker-active
      ::m/build-status])}

  (let [{::m/keys [build-id build-status build-worker-active]} props]
    (build-panel-container
      (build-panel-header
        (build-panel-label {:href (str "/build/" (name build-id))} (name build-id)))

      (build-panel-status
        (build-status/render-build-status build-status))

      #_(if build-worker-active
          (build-panel-toolbar
            (build-panel-action {:onClick #(fp/transact! this [(tx/build-watch-compile {:build-id build-id})])} "force-compile")
            (build-panel-action {:onClick #(fp/transact! this [(tx/build-watch-stop {:build-id build-id})])} "stop watch"))

          (build-panel-toolbar
            (build-panel-action {:onClick #(fp/transact! this [(tx/build-watch-start {:build-id build-id})])} "start watch")
            (build-panel-action {:onClick #(fp/transact! this [(tx/build-compile {:build-id build-id})])} "compile")
            (build-panel-action {:onClick #(fp/transact! this [(tx/build-release {:build-id build-id})])} "release"))
          ))))

(def ui-build-panel (fp/factory BuildPanel {:keyfn ::m/build-id}))
