(ns test.infer-test
  (:require [cljs.test :as ct :refer (use-fixtures deftest is async testing)]))

(use-fixtures :once
  {:before
   (fn []
     (js/console.log "fixture-once-before"))
   :after
   (fn []
     (js/console.log "fixture-once-after"))})

(use-fixtures :each
  {:before
   (fn []
     (js/console.log "fixture-each-before"))
   :after
   (fn []
     (js/console.log "fixture-each-after"))})

(defn obj-property [^js thing]
  (.inferMe thing))

(deftest can-properly-call-obj
  (is (true? (obj-property #js {"inferMe" (constantly true)}))))

(deftest dummy-test
  (testing "dummy nested"
    (is (= 1 1))))

(deftest async-text
  (async done
    (is (= 1 1))
    (println "going async")
    (js/setTimeout
      (fn []
        (is (= 1 1))
        (println "done async")
        (done))
      100)))
