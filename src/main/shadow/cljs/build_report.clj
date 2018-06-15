(ns shadow.cljs.build-report
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.tools.cli :as cli]
    [hiccup.page :refer (html5)]
    [shadow.build.output :as output]
    [shadow.server.assets :as assets]
    [shadow.core-ext :as core-ext]
    [shadow.build :as build]
    [shadow.build.api :as build-api]
    [shadow.cljs.devtools.server.util :as util]
    [shadow.cljs.devtools.api :as api]))

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

(defn generate
  [build-id {:keys [tag print-table report-file] :or {tag "latest"} :as opts}]
  {:pre [(keyword? build-id)
         (string? tag)
         (seq tag)]}
  (api/with-runtime
    (let [{:keys [cache-root]}
          (api/get-config)

          output-dir
          (doto (io/file cache-root "release-snapshots" (name build-id) tag)
            (output/clean-dir))

          build-id-alias
          (keyword (str (name build-id) "-release-snapshot"))

          build-config
          (-> (api/get-build-config build-id)
              (assoc
                :build-id build-id-alias
                ;; not required, the files are never going to be used
                :module-hash-names false))

          state
          (-> (util/new-build build-config :release {})
              (build/configure :release build-config)
              (build-api/enable-source-maps)
              (assoc-in [:build-options :output-dir] (io/file output-dir))
              (build/compile)
              (build/optimize)
              (build/flush))

          bundle-info
          (output/generate-bundle-info state)

          report-file
          (or (and (seq report-file) (io/file report-file))
              (io/file output-dir "report.html"))]

      (spit
        (io/file output-dir "bundle-info.edn")
        (core-ext/safe-pr-str bundle-info))

      (spit
        report-file
        (html5
          {}
          [:head
           [:title (format "[%s] Build Report - shadow-cljs" (name build-id))]
           [:meta {:charset "utf-8"}]]
          [:body
           [:div#root]
           (assets/js-queue :none 'shadow.cljs.ui.bundle-info/init bundle-info)
           [:style
            (slurp (io/resource "shadow/cljs/release-snapshot/dist/css/main.css"))]
           [:script {:type "text/javascript"}
            (slurp (io/resource "shadow/cljs/release-snapshot/dist/js/main.js"))]
           ]))

      (when print-table
        (print-bundle-info-table bundle-info {:group-npm true}))

      (println)
      (println (format "HTML Report generated in: %s" (.getAbsolutePath report-file)))

      :done)))

;; FIXME: parse args properly, need support for tag and output-dir
(defn -main [build-id report-file]
  (generate (keyword build-id) {:print-table true
                                :report-file report-file}))