(ns demo.hooks
  (:require [shadow.cljs.devtools.api :as api]))

(defn dummy
  {:shadow.build/stage :flush}
  [state & args]
  (prn [::flush args])
  state)
