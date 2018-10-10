(ns shadow.cljs.ui.env)

(defonce app-ref (atom nil))

(defmulti read-local (fn [env key params] key) :default ::default)

(defmethod read-local ::default [_ _ _]
  nil)