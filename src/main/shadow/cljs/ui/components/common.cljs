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

(def icon-cog
  ;; https://github.com/sschoger/heroicons-ui/blob/master/svg/icon-cog.svg
  (<< [:svg {:xmlns "http://www.w3.org/2000/svg"
             :viewBox "0 0 24 24"
             :width "24"
             :height "24"}
       [:path
        {:d "M9 4.58V4c0-1.1.9-2 2-2h2a2 2 0 0 1 2 2v.58a8 8 0 0 1 1.92 1.11l.5-.29a2 2 0 0 1 2.74.73l1 1.74a2 2 0 0 1-.73 2.73l-.5.29a8.06 8.06 0 0 1 0 2.22l.5.3a2 2 0 0 1 .73 2.72l-1 1.74a2 2 0 0 1-2.73.73l-.5-.3A8 8 0 0 1 15 19.43V20a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-.58a8 8 0 0 1-1.92-1.11l-.5.29a2 2 0 0 1-2.74-.73l-1-1.74a2 2 0 0 1 .73-2.73l.5-.29a8.06 8.06 0 0 1 0-2.22l-.5-.3a2 2 0 0 1-.73-2.72l1-1.74a2 2 0 0 1 2.73-.73l.5.3A8 8 0 0 1 9 4.57zM7.88 7.64l-.54.51-1.77-1.02-1 1.74 1.76 1.01-.17.73a6.02 6.02 0 0 0 0 2.78l.17.73-1.76 1.01 1 1.74 1.77-1.02.54.51a6 6 0 0 0 2.4 1.4l.72.2V20h2v-2.04l.71-.2a6 6 0 0 0 2.41-1.4l.54-.51 1.77 1.02 1-1.74-1.76-1.01.17-.73a6.02 6.02 0 0 0 0-2.78l-.17-.73 1.76-1.01-1-1.74-1.77 1.02-.54-.51a6 6 0 0 0-2.4-1.4l-.72-.2V4h-2v2.04l-.71.2a6 6 0 0 0-2.41 1.4zM12 16a4 4 0 1 1 0-8 4 4 0 0 1 0 8zm0-2a2 2 0 1 0 0-4 2 2 0 0 0 0 4z"}]]))

(def icon-compiling
  (<< [:span
       {:class (css :h-6 :w-6 :bg-blue-100 :rounded-full :flex :items-center :justify-center)
        :aria-hidden "true"}
       [:span
        {:class (css :h-3 :w-3 :bg-blue-400 :rounded-full)}]]))

(def icon-warnings
  (<< [:span
       {:class (css :h-6 :w-6 :bg-green-100 :rounded-full :flex :items-center :justify-center)
        :aria-hidden "true"}
       [:span
        {:class (css :h-3 :w-3 :bg-green-400 :rounded-full)}]]))

(def icon-completed
  (<< [:span
       {:class (css :h-6 :w-6 :bg-yellow-100 :rounded-full :flex :items-center :justify-center)
        :aria-hidden "true"}
       [:span
        {:class (css :h-3 :w-3 :bg-yellow-400 :rounded-full)}]]))

(def icon-failed
  (<< [:span
       {:class (css :h-6 :w-6 :bg-red-100 :rounded-full :flex :items-center :justify-center)
        :aria-hidden "true"}
       [:span
        {:class (css :h-3 :w-3 :bg-red-400 :rounded-full)}]]))

(def icon-inactive
  (<< [:span
       {:class (css :h-6 :w-6 :bg-gray-100 :rounded-full :flex :items-center :justify-center)
        :aria-hidden "true"}
       [:span
        {:class (css :h-3 :w-3 :bg-gray-400 :rounded-full)}]]))

(def icon-pending
  (<< [:span
       {:class (css :h-6 :w-6 :bg-blue-100 :rounded-full :flex :items-center :justify-center)
        :aria-hidden "true"}
       [:span
        {:class (css :h-3 :w-3 :bg-blue-400 :rounded-full)}]]))

(def card-button-class
  (css
    :font-medium :border-r :py-3 :px-5 :bg-gray-50
    ["&:hover" :bg-gray-300]
    ["&:focus" :outline-none :ring-2 :ring-offset-2 :ring-gray-500]))
