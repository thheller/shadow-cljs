(ns shadow.devtools.cli-test
  (:use clojure.test)
  (:require [shadow.devtools.cli :as cli]
            [clojure.pprint :refer (pprint)]))




(deftest test-config-spec
  (pprint (cli/release :website)))
