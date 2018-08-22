(ns demo.stuff
  (:require ["./foo" :as foo]))

(defmulti foo ::bar)
