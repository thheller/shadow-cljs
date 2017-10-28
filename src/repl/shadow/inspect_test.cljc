(ns shadow.inspect-test
  (:require [clojure.test :refer (deftest is)]
            [clojure.pprint :refer (pprint)]))

(def store-ref (atom {:values {}}))

(defn conj-vec [x y]
  (if (nil? x)
    [y]
    (conj x y)))

(defn inspect-many [id value]
  (swap! store-ref update-in [:values id] conj-vec value))

(defn describe [value]
  (cond
    (string? value)
    {:type :string
     :value value}

    (number? value)
    {:type :number
     :value value}

    ))

(defn query [path opts]
  (let [value (get-in @store-ref (into [:values] path))]
    (describe value)
    ))

(deftest test-a-thing
  (inspect-many ::foo 1)
  (inspect-many ::foo 2)
  (inspect-many ::foo 3)
  (inspect-many ::foo 4)
  (pprint @store-ref)
  (let []))