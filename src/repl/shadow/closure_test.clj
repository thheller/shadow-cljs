(ns shadow.closure-test
  (:require [clojure.test :as t :refer :all])
  (:import (com.google.javascript.jscomp JSChunk JSChunkGraph)))


(deftest test-js-module-graph-weirdness
  (let [mod-a
        (JSChunk. "a")

        mod-b
        (JSChunk. "b")

        mod-c
        (JSChunk. "c")]

    (.addDependency mod-c mod-a)
    (.addDependency mod-c mod-b)

    (let [graph
          (JSChunkGraph. (into-array [mod-a mod-b mod-c]))]

      (is (.dependsOn graph mod-c mod-a))
      (is (.dependsOn graph mod-c mod-b))
      (is (not (.dependsOn graph mod-b mod-c)))
      )))