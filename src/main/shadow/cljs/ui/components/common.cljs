(ns shadow.cljs.ui.components.common
  (:require [shadow.experiments.grove :refer (<<)]))

(def svg-close
  ;; https://github.com/sschoger/heroicons-ui/blob/master/svg/icon-x-square.svg
  (<< [:svg
       {:xmlns "http://www.w3.org/2000/svg"
        :viewBox "0 0 24 24"
        :width "24"
        :height "24"}
       [:path
        {:d "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5c0-1.1.9-2 2-2zm0 2v14h14V5H5zm8.41 7l1.42 1.41a1 1 0 1 1-1.42 1.42L12 13.4l-1.41 1.42a1 1 0 1 1-1.42-1.42L10.6 12l-1.42-1.41a1 1 0 1 1 1.42-1.42L12 10.6l1.41-1.42a1 1 0 1 1 1.42 1.42L13.4 12z"}]]))
