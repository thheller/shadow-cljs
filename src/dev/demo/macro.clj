(ns demo.macro
  (:require [demo.macro-dep :as dep]))

(defmacro foo [& body]
  (dep/foo))
