(ns demo.hooks
  (:require [shadow.cljs.devtools.api :as api]))

(defn dummy
  {:shadow.build/stage :flush}
  [state & args]
  (api/send-to-runtimes! state :hello-world-from-demo-hooks)
  (prn [::flush args])
  state)
