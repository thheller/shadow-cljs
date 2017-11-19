(ns shadow.cljs.ui-test
  (:require
    [clojure.test :refer (deftest is)]
    [clojure.pprint :refer (pprint)]
    [shadow.cljs.devtools.api :as api]
    [shadow.build.closure :as closure]
    [shadow.cljs.util :as util]
    [shadow.build.data :as data]))

(deftest test-bundle-info

  (let [report
        (api/generate-bundle-info :browser)]

    (pprint report)))
