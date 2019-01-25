(ns test.infer-test
  (:require [cljs.test :refer (use-fixtures deftest is)]))

#_(use-fixtures :once
    (fn [done]
      (js/console.log :once)
      (done)))

#_(use-fixtures :each
    (fn [done]
      (js/console.log :each)
      (done)))

(defn obj-property [^js thing]
  (.inferMe thing))

(deftest can-properly-call-obj
  (is (true? (obj-property #js {"inferMe" (constantly true)}))))

(deftest dummy-test
  (is (= 1 1)))
