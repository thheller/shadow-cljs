(ns shadow.cljs.ui.components.repl
  (:require
    [shadow.cljs :as-alias m]
    [shadow.grove.events :as ev]
    [shadow.grove.kv :as kv]
    [shadow.cljs.ui.components.inspect :as inspect]
    ))



(defn ui-page []
  ;; abusing inspect for this for now
  (inspect/ui-page))

