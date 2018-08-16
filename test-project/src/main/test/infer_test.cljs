(ns test.infer-test
  (:require [cljs.test :refer (deftest is)]))

(defn obj-property [^js thing]
  (.inferMe thing))

(deftest can-properly-call-obj
  (is (true? (obj-property #js {"inferMe" (constantly true)}))))

