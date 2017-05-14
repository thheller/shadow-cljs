(ns shadow.npm
  (:require-macros [shadow.npm :as m]))

(defn env []
  js/CLJS_ENV)
