(ns shadow.cljs.ui.build-list
  (:require [shadow.markup.react :as html :refer (defstyled)]
            [shadow.react.component :as comp :refer (deffactory)]
            [shadow.vault.store :as store]
            [shadow.cljs.ui.common :as common]
            ))

(defstyled title :div
  [env]
  {:font-size "1.2em"
   :margin-bottom 10})

(defstyled list-container :div
  [env]
  {})

(defstyled list-item :div
  [env]
  {})

(deffactory container
  ::comp/mixins
  [store/mixin]

  ::store/read
  (fn [this vault props]
    {:foo "bar"})

  ::store/render
  (fn [this vault props {:keys [builds] :as data}]
    (js/console.log ::render props data)

    (if (nil? builds)
      (common/loading)
      (list-container
        (title "builds")

        (html/for [build builds]
          (list-item build)
          )))
    ))
