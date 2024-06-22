(ns demo.esm.c
  (:require
    [demo.esm.b :as b]
    ["dummy" :as d]))

(def ^:export foo (str b/bar "+demo.esm.c/foo"))

(def ^:export bar (d/foo "c"))


