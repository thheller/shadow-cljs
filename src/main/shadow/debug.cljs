(ns shadow.debug
  (:require-macros [shadow.debug]))

(defn tap-> [obj opts]
  (tap> [:shadow.remote/wrap obj opts])
  obj)