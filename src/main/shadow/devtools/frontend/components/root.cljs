(ns shadow.devtools.frontend.components.root
  (:require [shadow.markup.react :as html :refer (defstyled)]
            [shadow.react.component :as comp :refer (deffactory)]
            [shadow.react.multi :as multi]
            [shadow.util :refer (go!)]
            [shadow.vault.store :as store]
            [shadow.devtools.frontend.components.api :as api]
            [shadow.devtools.frontend.components.dashboard :as dashboard]
            [shadow.devtools.frontend.components.actions :as a]
            ))

(defstyled root :div
  [env]
  {})

(defstyled menu :div
  [env]
  {})

(defstyled content :div
  [env]
  {})

(deffactory sections
  (-> {::comp/type
       ::sections

       ::multi/states
       {:dashboard
        dashboard/container}}

      (multi/extend)
      ))

(deffactory container
  (-> {::comp/will-mount
       (fn [{::store/keys [vault] :as this}]
         (go! (when-some [builds (<! (api/load-builds))]
                (store/transact! vault [(a/import-builds builds)])))
         this)

       ::comp/render
       (fn [this]
         (root {}
           (menu {} "menu")
           (content {}
             (sections {}))))}
      (store/component)))
