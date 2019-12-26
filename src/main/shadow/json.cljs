(ns shadow.json
  (:require [goog.object :as gobj]))

(defn to-clj
  "simplified js->clj for JSON data, :key-fn default to keyword"
  ([x] (to-clj x {}))
  ([x opts]
   (cond
     (nil? x)
     x

     (number? x)
     x

     (string? x)
     x

     (boolean? x)
     x

     (array? x)
     (into [] (map #(to-clj % opts)) (array-seq x))

     :else ;; object
     (let [key-fn (get opts :key-fn keyword)]
       (->> (gobj/getKeys x)
            (reduce
              (fn [result key]
                (let [value (gobj/get x key)]
                  (assoc! result
                    (if (string? key)
                      (key-fn key)
                      (to-clj key opts))
                    (to-clj value opts))))
              (transient {}))
            (persistent!))))))

(defn read-str [str opts]
  (to-clj (js/JSON.parse str) opts))

(defn write-str [obj]
  (-> (clj->js obj)
      (js/JSON.stringify)))