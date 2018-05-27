(ns demo.hooks)

(defn dummy
  {:shadow.build/stage :flush}
  [state & args]
  (prn [::flush args])
  state)
