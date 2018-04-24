(ns shadow.cljs.devtools.release-snapshot
  (:require
    [clojure.string :as str]))

(defn format-byte-size [size]
  (loop [i 0 size (double size)]
    (if (< size 1000)
      (format "%.1f %s" size (nth ["B " "KB" "MB" "GB" "TB" "PB"] i))
      (recur (inc i) (/ size 1000)))))

(defn get-optimized-info [build-modules]
  (->> build-modules
       (map :source-bytes)
       (reduce (partial merge-with +))))

(defn get-bundle-summary
  [{:keys [build-modules build-sources] :as bundle-info}
   {:keys [group-jar group-npm]
    :or {group-jar false
         group-npm true}}]
  (let [optimized (get-optimized-info build-modules)]
    (->> build-sources
         (map (fn [{:keys [pom-info package-name resource-name js-size source-size]}]
                {:group (or (and package-name
                                 [:npm (if group-npm package-name resource-name)])
                            (and pom-info
                                 [:jar (if group-jar (str (:id pom-info)) resource-name)])
                            [:loc resource-name])
                 :js-size js-size
                 :source-size source-size
                 :optimized-size (get optimized resource-name 0)}))
         (group-by :group)
         (map (fn [[group items]]
                (->> items
                     (map #(dissoc % :group))
                     (reduce (partial merge-with +)
                       {:group group
                        :items (sort-by :optimized-size > items)}))))
         (into [])
         )))

(defn print-bundle-info-table [bundle-info opts]
  (let [items
        (->> (get-bundle-summary bundle-info opts)
             (remove #(zero? (:optimized-size %)))
             (sort-by :optimized-size >))

        total-size
        (->> items
             (map :optimized-size)
             (reduce + 0))

        max-resource-len
        (->> items
             (map #(-> % :group second count))
             (reduce max 0)
             (+ 2))]

    (println
      (str "| Resource " (->> " " (repeat (- max-resource-len 2)) (str/join ""))
           "|   Optimized "
           "|  Total % "
           "|          JS "
           "|      Source |"))

    (println
      (str "|----------" (->> "-" (repeat (- max-resource-len 2)) (str/join ""))
           "+-------------"
           "+----------"
           "+-------------"
           "+-------------"
           "|"))

    (doseq [{:keys [group source-size js-size optimized-size]} items]
      (let [[pkg resource-name] group]

        (println
          (str "| "
               (case pkg
                 :loc
                 "   "
                 (name pkg))
               " | "
               resource-name (->> " " (repeat (- max-resource-len (count resource-name))) (str/join ""))
               " | " (format "%11s" (format-byte-size optimized-size))
               " | " (format "%8s" (format "%.2f %%" (* 100 (double (/ optimized-size total-size)))))
               " | " (format "%11s" (format-byte-size js-size))
               " | " (format "%11s" (format-byte-size source-size))
               " |"
               )))
      )))
