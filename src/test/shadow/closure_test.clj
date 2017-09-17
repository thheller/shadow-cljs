(ns shadow.closure-test
  (:require [clojure.test :refer :all])
  (:import (com.google.javascript.jscomp JSModule JSModuleGraph)))


(deftest test-js-module-graph-weirdness
  (let [mod-a
        (JSModule. "a")

        mod-b
        (JSModule. "b")

        mod-c
        (JSModule. "c")]

    (.addDependency mod-c mod-a)
    (.addDependency mod-c mod-b)

    (let [graph
          (JSModuleGraph. (into-array [mod-a mod-b mod-c]))]

      (is (.dependsOn graph mod-c mod-a))
      (is (.dependsOn graph mod-c mod-b))
      (is (not (.dependsOn graph mod-b mod-c)))
      )))