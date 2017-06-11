(ns shadow.cljs.ui.build-list
  (:require [shadow.markup.react :as html :refer (defstyled)]
            [shadow.react.component :as comp :refer (deffactory)]
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
  ::comp/render
  (fn [this]
    (list-container
      (title "builds")
      (list-item "foo")
      (list-item "bar")
      )))
