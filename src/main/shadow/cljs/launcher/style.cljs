(ns shadow.cljs.launcher.style
  (:require [shadow.markup.react :as html :refer (defstyled)]))

(defstyled app-frame :div
  [env]
  {:position "fixed"
   :top 0
   :left 0
   :width "100%"
   :height "100%"
   :overflow "hidden"})

(defstyled main-cols :div
  [env]
  {:display "flex"
   :height "100%"
   :width "100%"})

(defstyled main-contents :div
  [env]
  {:flex 1
   :overflow "auto"
   })

(defstyled project-info-container :div
  [env]
  {:padding [10 10 0 10]
   :border-top "2px solid #ccc"
   })

(defstyled right-action-button :button
  [env]
  {:float "right"})

(defstyled project-info-item :div
  [env]
  {:margin-bottom 10

   "&:last-child"
   {:margin-bottom 0}})

(defstyled project-info-label :div
  [env]
  {:font-weight "bold"})

(defstyled project-info-value :div
  [env]
  {})

(defstyled project-console-container :div
  [env]
  {:flex 1
   :background-color "white"
   :border-top "2px solid #ccc"
   :margin-top 10
   :display "flex"
   :flex-direction "column"})

(defstyled project-console-header :div
  [env]
  {:padding [5 10]
   :font-weight "bold"})

(defstyled project-console :div
  [env]
  {:flex 1
   :padding [0 10]})

(defstyled main-sidebar :div [env]
  {:z-index 100
   :position "fixed"
   :top 0
   :left 0
   :width 400
   :height "100%"
   :background-color "#fff"
   :transition "transform .2s ease"
   :transform "translate3d(-100%,0,0)"

   "&.expanded"
   {:box-shadow "0 0 20px #ccc"
    :transform "translate3d(0,0,0)"}

   "&.maximized"
   {:transition "none"
    :transform "translate3d(0,0,0)"
    :width "100%"}})

(defstyled project-listing :div
  [env]
  {:height "100%"
   :overflow "auto"
   :display "flex"
   :flex-direction "column"})

(defstyled project-listing-title :div
  [env]
  {:font-weight "bold"
   :font-size "1.2em"
   :padding 10})

(defstyled project-listing-items :div
  [env]
  {:flex 1
   :overflow "auto"
   :-webkit-user-select "none"})

(defstyled project-listing-item :div
  [env]
  {:padding 10
   "&.active"
   {:color "green"}
   "&:hover, &.selected"
   {:background-color "#efefef"
    :cursor "pointer"}})

(defstyled project-listing-actions :div [env]
  {:padding 10
   :display "flex"})

(defstyled logo-header :div [env]
  {:text-align "center"})

(defstyled logo-title :div [env]
  {:font-size "1.5em"})

(defstyled logo-img :img [env]
  {:padding 20
   :width 160})

(defstyled splash-container :div [env]
  {:flex 1
   :background "linear-gradient(to bottom, #ccc 0%, #f4f4f4 10px, #f4f4f4 100%)"
   :margin-left 400})

(defstyled project-container :div [env]
  {:flex 1
   :display "flex"
   :flex-direction "column"
   :background-color "#f4f4f4"
   })

(defstyled project-toolbar :div [env]
  {:display "flex"
   :background-color "#fff"
   })

(defstyled project-listing-link :div [env]
  {:font-weight "bold"
   :font-size "1.2em"
   :cursor "pointer"
   :padding 10})

(defstyled project-title :div [env]
  {:flex 1
   :overflow "hidden"
   :font-weight "bold"
   :font-size "1.2em"
   :padding [10 0]})

(defstyled project-actions :div [env]
  {:padding 10})

(defstyled project-action :button [env]
  {:font-family "monospace"
   :display "inline-block"
   :padding [0 5]
   :margin [0 10 0 0]

   "&:last-child"
   {:margin-right 0}})

(defstyled project-iframe :iframe [env]
  {:flex 1
   :border "none"})