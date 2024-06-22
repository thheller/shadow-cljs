(ns demo.esm.b
  (:require
    ["dummy" :as d]))

(def ^:export bar (str :bar "demo.esm.b/bar"))

(def ^:export default (d/foo "demo.esm.b/default"))

