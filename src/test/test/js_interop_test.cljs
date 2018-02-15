(ns test.js-interop-test
  (:require
    [cljs.test :refer (deftest is)]
    ["./cjs" :as cjs]
    ["./es6" :as es6 :default es6-default]
    ["./converted-esm" :as converted-esm :default converted-esm-default]
    ))

(comment
  (js/console.log "cjs" cjs)
  (js/console.log "es6" es6)
  (js/console.log "esm" converted-esm))

(deftest cjs-as
  (is (some? cjs))
  (is (= cjs/foo "cjs/foo"))
  (is (= cjs/bar "cjs/bar")))

(deftest es6-as
  (is (some? es6))
  (is (= es6/foo "es6/foo"))
  (is (= es6/bar "cjs/bar"))
  (is (map? es6/map))
  (is (= 1 (get es6/map "a")))
  (is (= es6-default "es6-default")))

(deftest converted-esm-as
  (is (some? converted-esm))
  (is (= converted-esm/foo "esm/foo"))
  (is (= converted-esm/bar "cjs/bar"))
  (is (= converted-esm-default "esm-default")))