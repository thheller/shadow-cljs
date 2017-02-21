(ns shadow.devtools.frontend.components.dashboard
  (:require [shadow.markup.react :as h :refer (defstyled)]
            [shadow.react.component :as comp :refer (deffactory)]
            [shadow.vault.store :as store]
            [shadow.devtools.frontend.components.model :as m]
            ))

(deffactory build-list-item
  (-> {::store/read
       (fn [this vault {:keys [build] :as props}]
         {:build (get vault build)})

       ::store/render
       (fn [this vault props {:keys [build] :as data}]
         (let [{:keys [id target]} build]
           (h/tr {}
             (h/td {} (str target))
             (h/td {} (str id))
             )))}

      (store/component)))

(deffactory container
  (-> {::store/read
       (fn [this vault props]
         {:builds (get vault m/Builds)})

       ::store/render
       (fn [this vault props {:keys [builds] :as data}]
         (h/div {}
           (h/h1 "Dashboard")
           (h/table
             (h/tbody
               (h/for [build builds]
                 (build-list-item {:build build}))))))}

      (store/component)))

