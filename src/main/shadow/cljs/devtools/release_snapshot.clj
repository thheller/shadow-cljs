(ns shadow.cljs.devtools.release-snapshot
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [shadow.build :as build]))

(defn format-byte-size [size]
  (loop [i 0 size (double size)]
    (if (< size 1000)
      (format "%.1f %s" size (nth ["B" "KB" "MB" "GB" "TB" "PB"] i))
      (recur (inc i) (/ size 1000)))))

(defn print-table [ks rows]
  (print "\u001B[1m")
  ;(print "\u001B[33m")
  (pprint/print-table ks rows)
  (print "\u001B[m"))

(defn get-optimized-info [build-modules]
  (->> build-modules
       (map :source-bytes)
       (reduce (partial merge-with +))))

(defn get-bundle-summary [{:keys [build-modules build-sources] :as bundle-info}]
  (let [optimized (get-optimized-info build-modules)]
    (->> build-sources
         (map (fn [{:keys [package-name resource-name js-size source-size]}]
                {:group (or package-name resource-name)
                 :js-size js-size
                 :source-size source-size
                 :optimized-size (get optimized resource-name 0)}))
         (group-by :group)
         (map (fn [[group items]]
                (->> items
                     (map #(dissoc % :group))
                     (reduce (partial merge-with +) {:group group}))))
         (into [])
         )))

(defn print-bundle-info-table [bundle-info]
  (->> (get-bundle-summary bundle-info)
       (remove #(zero? (:optimized-size %)))
       (sort-by :optimized-size >)
       (map (fn [{:keys [group source-size js-size optimized-size]}]
              {"Source" group
               "Optimized size" (when (pos? optimized-size) (format-byte-size optimized-size))
               "Source size" (format-byte-size source-size)
               "JS size" (format-byte-size js-size)}))
       (print-table ["Source" "Optimized size" "JS size" "Source size"])))
