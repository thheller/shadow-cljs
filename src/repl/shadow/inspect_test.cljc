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
    (nil? value)
    {:type :nil}

    (string? value)
    {:type :string
     :value value}

    (number? value)
    {:type :number
     :value value}

    (boolean? value)
    {:type :boolean
     :value value}

    (or (record? value)
        (map? value))
    {:type :kv
     :type-desc (pr-str (type value))
     :count (count value)}

    (indexed? value)
    {:type :seq-idx
     :type-desc (pr-str (type value))}

    (sequential? value)
    {:type :seq
     :type-desc (pr-str (type value))
     :realized? (realized? value)}

    (coll? value)
    {:type :coll
     :type-desc (pr-str (type value))}

    :else
    {:type :unknown
     :type-desc (pr-str (type value))}
    ))

(defn query [path opts]
  (let [value (get-in @store-ref (into [:values] path))]
    (describe value)
    ))

(deftest test-a-thing
  (let [test-val
        {:some
         [:thing
          {:nested #{1 2 3}}]}]


    (pprint (describe test-val))
    (pprint (describe (get-in test-val [:some 1 :nested])))
    )
  )