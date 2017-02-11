(ns shadow.devtools.frontend.components.dashboard
  (:require [shadow.markup.react :as html :refer (defstyled)]
            [shadow.react.component :as comp :refer (deffactory)]))

(deffactory container
  {::comp/render
   (fn [this]
     (html/h1 "Dashboard"))})

