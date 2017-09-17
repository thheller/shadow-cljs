(ns shadow.cljs.test-util
  (:require [clojure.pprint :as pp :refer (pprint)]))

(defn wide-pprint [x]
  (binding [clojure.pprint/*print-right-margin* 120]
    (pprint x)))


