(ns shadow.devtools.cli-test
  (:use clojure.test)
  (:require [shadow.devtools.cli :as cli]
            [clojure.pprint :refer (pprint)]))


(deftest test-config-spec
  (cli/load-cljs-edn!))

(deftest test-once
  (cli/once "browser"))