(ns test.js-interop-test
  (:require
    [cljs.test :refer (deftest is)]
    ["./cjs" :as cjs]
    ["./es6" :as es6 :default es6-default]))

(deftest cjs-as
  (is (some? cjs))
  (is (= cjs/foo "cjs/foo")))

(deftest es6-as
  (is (some? es6))
  (is (= es6/foo "es6/foo"))
  (is (= es6-default "es6-default")))