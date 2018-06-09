(ns demo.test-fail
  (:require [cljs.test :refer [deftest is]]))

(deftest a-failing-test
  (is (= 1 2) "it should fail")

123
