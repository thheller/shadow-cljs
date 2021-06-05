(ns test.js-interop-test
  (:require
    [cljs.test :refer (deftest is)]
    ["./cjs.js" :as cjs]
    ["./es6.js" :as es6 :default es6-default]
    ["./es6.js$default" :as es6-default-sugar]
    ["./es6.js$nested.extra" :refer (bar)]
    ["./es6.js$nested.extra.bar" :as bar-direct]
    ["./converted-esm.js" :as converted-esm :default converted-esm-default]
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
  (is (map? es6/map))
  (is (= 1 (get es6/map "a")))
  (is (= es6-default "es6-default"))
  (is (identical? es6-default es6-default-sugar))
  (is (identical? es6/nested.extra.bar bar))
  (is (identical? bar bar-direct)))

;; this fails, unsure what to do about this
;; https://github.com/thheller/shadow-cljs/issues/894
#_(deftest es6-indirect
    ;; accessing a const from other import
    ;; fails because const in eval has its own scope and other evals can't see it
    (is (= "foo" (es6/other-foo))))