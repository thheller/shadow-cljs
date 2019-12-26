(ns shadow.cljs.ui.style
  ;; {:shadow.markup.css/alias "ui"}

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

(defstyled page-title :div
  [env]
  {:font-size "1.8em"
   :font-weight "bold"
   :padding [10 0]})

(defstyled cards-title :div
  [env]
  {:font-size "1.8em"
   :font-weight "bold"
   :padding [10 0 10 20]})

(defstyled toolbar-actions :div
  [env]
  {})

(defstyled toolbar-title :div
  [env]
  {:display "inline-block"
   :font-size 18
   :margin-right 20})

(defstyled toolbar-action :button
  [env]
  {:display "inline-block"
   :background-color "white"
   :border "1px solid #ccc"
   :padding 10
   :margin-right 10
   "&:hover"
   {:background-color "#ccc"}})

(defstyled simple-toolbar :div
  [env]
  {:display "flex"})

(defstyled toolbar-right :div
  [env]
  {:text-align "right"
   :flex 1})

(defstyled build-config :div
  [env]
  {})

(defstyled build-log :div
  [env]
  {})

(defstyled build-section :div
  [env]
  {:padding [10 0]})

(defstyled build-section-title :div
  [env]
  {:font-weight "bold"})

(defstyled build-log-entry :div
  [env]
  {:font-family "monospace"})

(defstyled page-container :div [env]
  {:position "fixed"
   :background-color "#f4f4f4"
   :top 0
   :left 0
   :width "100%"
   :height "100%"
   :display "flex"
   :flex-direction "column"})

(defstyled main-nav :div [env]
  {:color "#000"
   :background-color "#fff"
   :display "flex"
   :border-bottom "2px solid #ccc"
   :margin-bottom 5})

(defstyled main-nav-header :div [env]
  {})

(defstyled main-page :div [env]
  {:flex 1
   :display "flex"
   :flex-direction "column"})

(defstyled main-contents :div [env]
  {:flex 1
   :overflow "auto"
   :padding 10})

(defstyled main-header :div [env]
  {})

(defstyled page-contents :div [env]
  {})

(defstyled main-nav-title :div [env]
  {:font-weight "bold"
   :padding [10 20]
   :font-size 18})

(defstyled page-icons :div [env]
  {:text-align "right"})

(defstyled nav-items :div [env]
  {:display "flex"})

(defstyled nav-fill :div [env]
  {:flex 1})

(defstyled nav-item :div [env]
  {:padding [10 20]
   :position "relative"})

(defstyled nav-link :a [env]
  {:color "#000"
   :text-decoration "none"})

(defstyled nav-item-title :div [env]
  {:font-size 18})

(defstyled nav-sub-items :div [env]
  {:position "absolute"
   :top 40
   :left -10
   :width 280
   :background-color "#fff"
   :z-index 100
   :display "none"
   :box-shadow "0 10px 20px rgba(0,0,0,0.19), 0 6px 6px rgba(0,0,0,0.23)"
   :overflow "auto"
   :max-height 500

   [nav-item ":hover"]
   {:display "block"}})

(defstyled nav-sub-item :div [env]
  {:padding 10})

(defstyled nav-build-item :div [env]
  {:display "flex"
   "&:hover"
   {:background-color "#efefef"
    :font-weight "bold"}})

(defstyled nav-build-checkbox :div [env]
  {:padding 10})

(defstyled nav-build-link :a [env]
  {:flex 1
   :padding [10 0]})

(defstyled build-warning-container :div
  [env]
  {:padding [10 0]})

(defstyled build-warning-title :div
  [env]
  {:font-weight "bold"
   :padding [5 0 0 0]})

(defstyled build-warning-message :div
  [env]
  {:padding [10 0]
   :font-size "1.4em"})

(defstyled source-excerpt-container :div
  [env]
  {:padding 10
   :border "1px solid #eee"
   :background-color "#fff"
   :overflow-x "auto"})

(defstyled source-line :pre
  [env]
  {:margin 0
   :padding 0})

(defstyled source-line-highlight :pre
  [env]
  {:margin 0
   :padding 0
   :font-weight "bold"})

(defstyled source-line-part :span
  [env]
  {})

;; FIXME: warnings probably should not be red
(defstyled source-line-part-highlight :span
  [env]
  {:color "red"
   :border-bottom "2px solid red"})

(defstyled source-line-msg :pre
  [env]
  {:margin 0
   :padding [10 0]
   :font-weight "bold"
   :background-color "#eee" })