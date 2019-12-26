(ns shadow.cljs.ui.env
  (:require
    [com.fulcrologic.fulcro.networking.http-remote :as fhr]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as comp]
    [com.fulcrologic.fulcro.data-fetch :as df]))

(defonce app :done-in-init
  )

(defmulti read-local (fn [env key params] key) :default ::default)

(defmethod read-local ::default [_ _ _]
  nil)