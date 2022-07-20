(ns shadow.cljs.ui.components.common
  (:require
    [shadow.css :refer (css)]
    [shadow.grove :refer (<<)]))

(def icon-close
  ;; https://github.com/sschoger/heroicons-ui/blob/master/svg/icon-x-square.svg
  (<< [:svg
       {:xmlns "http://www.w3.org/2000/svg"
        :viewBox "0 0 24 24"
        :width "24"
        :height "24"}
       [:path
        {:d "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5c0-1.1.9-2 2-2zm0 2v14h14V5H5zm8.41 7l1.42 1.41a1 1 0 1 1-1.42 1.42L12 13.4l-1.41 1.42a1 1 0 1 1-1.42-1.42L10.6 12l-1.42-1.41a1 1 0 1 1 1.42-1.42L12 10.6l1.41-1.42a1 1 0 1 1 1.42 1.42L13.4 12z"}]]))

(def icon-compiling
  (<< [:span.h-6.w-6.bg-blue-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-blue-400.rounded-full]]))

(def icon-warnings
  (<< [:span.h-6.w-6.bg-green-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-green-400.rounded-full]]))

(def icon-completed
  (<< [:span.h-6.w-6.bg-yellow-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-yellow-400.rounded-full]]))

(def icon-failed
  (<< [:span.h-6.w-6.bg-red-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-red-400.rounded-full]]))

(def icon-inactive
  (<< [:span.h-6.w-6.bg-gray-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-gray-400.rounded-full]]))

(def icon-pending
  (<< [:span.h-6.w-6.bg-blue-100.rounded-full.flex.items-center.justify-center {:aria-hidden "true"}
       [:span.h-3.w-3.bg-blue-400.rounded-full]]))

(def card-button-class
  (css
    :font-medium :border-r :py-3 :px-5 :bg-gray-50
    ["&:hover" :bg-gray-300]
    ["&:focus" :outline-none :ring-2 :ring-offset-2 :ring-gray-500]))
