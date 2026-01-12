(ns demo.esm.d
  (:require
   [clojure.string :as cs]))

(def ^:export bar (cs/join [(str :bar "demo.esm.d") "bar"] "/"))



