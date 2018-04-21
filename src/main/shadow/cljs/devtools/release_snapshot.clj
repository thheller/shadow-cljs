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

(defn short-resource-name [resource-name]
  (cond
    (str/includes? resource-name "node_modules")
    (second (str/split resource-name #"/"))
    :else resource-name))

(defn get-optimized-info [build-modules]
  (->> build-modules
       (map (fn [module]
              (->> (:source-bytes module)
                   (map (fn [[module-name bytes]] [module-name {:optimized-size bytes}]))
                   (into {}))))
       (reduce (partial merge-with +))))

(defn get-unoptimized-info [build-sources]
  (reduce
    (fn [m x]
      (assoc m (:resource-name x) (select-keys x [:js-size :source-size])))
    {}
    build-sources))

(defn get-bundle-summary [{:keys [build-modules build-sources] :as bundle-info}]
  (let [optimized (get-optimized-info build-modules)
        raw (get-unoptimized-info build-sources)
        merged (build/deep-merge optimized raw)]
    (->> merged
         (group-by (fn [[k _]] (short-resource-name k)))
         (map (fn [[k v]]
                (let [resource-totals (->> v (map second) (reduce (partial merge-with +)))]
                  (assoc resource-totals :short-resource-name k)))))))

(defn print-bundle-info-table [bundle-info]
  (->> (get-bundle-summary bundle-info)
       (sort-by #(or (:optimized-size %) 0) >)
       (map (fn [{:keys [short-resource-name source-size js-size optimized-size]}]
              {"Resource name" short-resource-name
               "Optimized size" (some-> optimized-size (format-byte-size))
               "Source size" (some-> source-size (format-byte-size))
               "JS size" (some-> js-size (format-byte-size))}))
       (print-table ["Resource name" "Optimized size" "JS size" "Source size"])))
