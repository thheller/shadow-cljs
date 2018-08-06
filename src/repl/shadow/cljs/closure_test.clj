(ns shadow.cljs.closure-test
  (:require [clojure.test :refer (deftest is)]
            [clojure.pprint :refer (pprint)]
            [shadow.build.closure :as closure]
            [shadow.build.data :as data])
  (:import (com.google.javascript.jscomp ShadowAccess)))


(deftest test-get-externs-properties
  (let [cc
        (data/make-closure-compiler)

        co
        (closure/make-options)

        externs
        closure/default-externs

        result
        (.compile cc externs [] co)]

    (prn (ShadowAccess/getExternProperties cc))
    ))
