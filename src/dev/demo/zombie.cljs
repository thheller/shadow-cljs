(ns demo.zombie
  (:require
    [cljs.test :refer (deftest is)]))

(defn init []
  ;; just some code to keep the CLJS collections alive
  (js/console.log [:hello {:who "World!"}]))

(deftest my-test
  (is (= :super :cool)))
