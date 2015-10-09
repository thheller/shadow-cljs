(ns test.app
  (:require [shadow.devtools :as dev :refer (dump)]))

(deftype MyCustomType [a b c])

(dev/register! MyCustomType #js {:tag (fn [v] "my-custom-type")
                                 :rep (fn [v] #js [(.-a v) (.-b v) (.-c v)])
                                 :stringRep (fn [v] nil)})

(defn ^:export start []
  (let [data {:a "hello world yo" :b [1 2 3] :c (MyCustomType. "AAA" "b" "C")}]
    (dump "some label" data)))
