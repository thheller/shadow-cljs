(ns demo.esm.c
  (:require [demo.esm.b :as b]))

(def foo (str b/bar "+demo.esm.c/foo"))


