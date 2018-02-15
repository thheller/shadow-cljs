(ns test.js-interop-test
  (:require
    [cljs.test :refer (deftest is)]
    ["./cjs" :as cjs]
    ["./es6" :as es6 :default es6-default]
    ["./converted-esm" :as converted-esm :default converted-esm-default]
    ))

(comment
  (js/console.log "cjs" cjs cjs/foo cjs/bar)
  (js/console.log "es6" es6 es6/foo es6/bar es6/map)
  (js/console.log "esm" converted-esm converted-esm/foo converted-esm/bar)

  (deftest cjs-as
    (is (some? cjs))
    (is (= cjs/foo "cjs/foo"))
    (is (= cjs/bar "cjs/bar")))

  (deftest converted-esm-as
    (is (some? converted-esm))
    (is (= converted-esm/foo "esm/foo"))
    (is (= converted-esm/bar "cjs/bar"))
    (is (= converted-esm-default "esm-default"))))

(deftest es6-as
  (is (some? es6))
  (is (= es6/foo "es6/foo"))
  #_ (is (= es6/bar "cjs/bar"))
  (is (map? es6/map))
  (is (= 1 (get es6/map "a")))
  (is (= es6-default "es6-default")))