(ns shadow.cljs.ui.style
  {:shadow.markup.css/alias "ui"}

  (:require [shadow.markup.react :as html :refer (defstyled)]))

;; https://material.io/design/color/the-color-system.html#tools-for-picking-colors
;; Indigo 50 - #E8EAF6
;; 100  #C5CAE9
;; 200  #9FA8DA
;; 300  #7986CB
;; 400  #5C6BC0
;; 500  #3F51B5
;; 600  #3949AB
;; 700  #303F9F
;; 800  #283593
;; 900  #1A237E
;; A100 #8C9EFF
;; A200 #536DFE
;; A400 #3D5AFE
;; A700 #304FFE

(defstyled build-items :div [env]
  {:padding 10})

(defstyled build-item :div [env]
  {:margin-bottom 10
   :padding 10})

(defstyled build-title :div
  [env]
  {:font-size "1.4em"
   :font-weight "bold"
   :padding [10 0]})

(defstyled build-actions :div
  [env]
  {})

(defstyled build-action :button
  [env]
  {:display "inline-block"
   :margin-right 10})

(defstyled build-toolbar :div
  [env]
  {})

(defstyled build-config :div
  [env]
  {})

(defstyled build-log :div
  [env]
  {})

(defstyled build-section :div
  [env]
  {:font-weight "bold"
   :padding [10 0]})

(defstyled page-container :div [env]
  {:position "absolute"
   :top 0
   :left 0
   :bottom 0
   :right 0
   :display "flex"})

(defstyled main-nav :div [env]
  {:width 200})

(def header-styles
  {:padding [20 10]
   :line-height 20
   :font-size 20
   :color "rgba(255,255,255,.87)"
   :background-color "#1A237E"
   :display "flex"})

(defstyled main-nav-header :div [env]
  header-styles)

(defstyled main-contents :div [env]
  {:flex 1})

(defstyled main-header :div [env]
  header-styles)

(defstyled page-contents :div [env]
  {})

(defstyled main-nav-title :div [env]
  {:font-weight "bold"
   :flex 1})

(defstyled page-icons :div [env]
  {:text-align "right"})

(defstyled nav-items :div [env]
  {})

(defstyled nav-item :div [env]
  {:padding 10
   :font-size 18
   })

(defstyled nav-sub-item :div [env]
  {:padding [5 10]
   :font-size 14
   })