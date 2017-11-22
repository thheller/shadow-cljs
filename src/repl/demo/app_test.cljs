(ns demo.app-test
  (:require [cljs.test :as ct :refer (deftest is)]))

(ct/use-fixtures :once
  {:before
   (fn []
     (js/console.log "once before"))
   :after
   (fn []
     (js/console.log "once after"))})

(ct/use-fixtures :each
  {:before
   (fn []
     (js/console.log "each before"))

   :after
   (fn []
     (js/console.log "each after"))})

(deftest a-failing-test
  (is (= 1 2)))

(deftest a-passing-test
  (is (= 1 1)))
