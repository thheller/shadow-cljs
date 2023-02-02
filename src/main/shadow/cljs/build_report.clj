(ns shadow.cljs.build-report
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [hiccup.page :refer (html5)]
    [shadow.build.npm :as npm]
    [shadow.build.output :as output]
    [shadow.server.assets :as assets]
    [shadow.core-ext :as core-ext]
    [shadow.build :as build]
    [shadow.build.api :as build-api]
    [shadow.build.log :as build-log]
    [shadow.build.data :as data]
    [shadow.cljs.util :as util]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.devtools.server.util :as server-util])
  (:import [shadow.build.closure SourceMapReport]
           [java.io ByteArrayOutputStream]
           [java.util.zip GZIPOutputStream]))

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

(defmethod build-log/event->str ::generate-bundle-info
  [event]
  "Generating build report data")

(defn extract-report-data
  [{:shadow.build.closure/keys [optimized-bytes modules] :keys [build-sources npm] :as state}]
  (util/with-logged-time [state {:type ::generate-bundle-info}]
    (let [modules-info
          (->> modules
               (map (fn [{:keys [module-id sources depends-on output-name entries] :as mod}]
                      (let [out-file
                            (data/output-file state output-name)

                            out-map-file
                            (data/output-file state (str output-name (get-in state [:compiler-options :source-map-suffix] ".map")))

                            byte-map
                            (SourceMapReport/getByteMap out-file out-map-file)

                            bytes-out
                            (ByteArrayOutputStream.)

                            zip-out
                            (GZIPOutputStream. bytes-out)]

                        (io/copy (slurp out-file) zip-out)
                        (.flush zip-out)
                        (.close zip-out)

                        {:module-id module-id
                         :sources sources
                         :depends-on depends-on
                         :source-bytes byte-map
                         :entries (set entries)
                         :js-size (.length out-file)
                         :gzip-size (.size bytes-out)}
                        )
                      ))
               (into []))

          src->mod
          (->> (for [{:keys [module-id sources] :as mod} modules
                     src sources]
                 [src module-id])
               (into {}))

          entry-set
          (->> modules
               (mapcat :entries)
               (set))

          sources-info
          (->> build-sources
               (map (fn [src-id]
                      (let [{:keys [resource-name fs-root pom-info package-name output-name type provides] :as src}
                            (data/get-source-by-id state src-id)

                            npm-info
                            (when-some [package (:shadow.build.npm/package src)]
                              (let [res (select-keys package [:package-id :package-name :version])]
                                (if (:package-name res)
                                  res
                                  ;; some package.json files only exist to override local entries, but contain no package-name
                                  ;; don't treat them as actual packages and traverse dir structure up till a
                                  ;; package.json with a name is found
                                  ;; doing this hero since during the build nothing is interested in the name, only the entry keys
                                  (loop [dir (.getParentFile (:package-dir package))]
                                    (if-not dir
                                      res
                                      (let [package-json (io/file dir "package.json")]
                                        (if-not (.exists package-json)
                                          (recur (.getParentFile dir))
                                          (let [content (npm/read-package-json npm package-json)]
                                            (if-not (:package-name content)
                                              (recur (.getParentFile dir))
                                              (select-keys content [:package-id :package-name :version])
                                              )))))))))

                            {:keys [js source] :as output}
                            (data/get-output! state src)]

                        (-> {:resource-id src-id
                             :resource-name resource-name
                             :module-id (get src->mod src-id)
                             :type type
                             :output-name output-name
                             :provides provides
                             :requires (into #{} (data/deps->syms state src))
                             :js-size (count js)}
                            (cond->
                              (seq package-name)
                              (assoc :package-name package-name)

                              fs-root
                              (assoc :fs-root fs-root)

                              pom-info
                              (assoc :pom-info pom-info)

                              npm-info
                              (assoc :npm-info npm-info)

                              (string? source)
                              (assoc :source-size (count source))

                              (and (= :cljs (:type src))
                                   (contains? entry-set (:ns src)))
                              (assoc :module-entry true)
                              )))))
               (into []))]

      {:build-modules modules-info
       :build-sources sources-info})))

(defn generate-html [build-state {:keys [build-id] :as bundle-info} report-file {:keys [inline] :or {inline true} :as opts}]
  (io/make-parents report-file)
  (spit
    report-file
    (html5
      {}
      [:head
       [:title (format "[%s] Build Report - shadow-cljs" (name (or build-id (:shadow.build/build-id build-state))))]
       [:meta {:charset "utf-8"}]]
      [:body
       [:div#root]
       (if inline
         [:style (slurp (io/resource "shadow/cljs/build_report/dist/css/main.css"))]
         [:link {:rel "stylesheet" :href "css/main.css"}])

       [:script {:type "shadow/build-report"}
        (core-ext/safe-pr-str bundle-info)]

       (if inline
         [:script {:type "text/javascript"} (slurp (io/resource "shadow/cljs/build_report/dist/js/main.js"))]
         [:script {:type "text/javascript" :src "js/main.js"}])
       ])))

(defn generate-edn [build-state bundle-info data-file opts]
  (io/make-parents data-file)
  (spit data-file (core-ext/safe-pr-str bundle-info)))

(defn generate
  [{:keys [build-id] :as build-config} {:keys [tag print-table report-file data-file] :or {tag "latest"} :as opts}]
  {:pre [(keyword? build-id)
         (map? build-config)
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
          (-> build-config
              (assoc
                :build-id build-id-alias
                ;; not required, the files are never going to be used
                :module-hash-names false)
              (dissoc :build-hooks))

          state
          (-> (server-util/new-build build-config :release {})
              (build/configure :release build-config opts)
              (build-api/enable-source-maps)
              (assoc-in [:build-options :output-dir] (io/file output-dir))
              (build/compile)
              (build/optimize)
              (build/flush))

          bundle-info
          (-> (extract-report-data state)
              (assoc :build-id build-id))

          report-file
          (and (seq report-file) (io/file report-file))

          data-file
          (and (seq data-file) (io/file data-file))]

      (when report-file
        (generate-html state bundle-info report-file opts))

      (when data-file
        (generate-edn state bundle-info data-file opts))

      (when print-table
        (print-bundle-info-table bundle-info {:group-npm true}))

      (println)
      (println (format "HTML Report generated in: %s" (.getAbsolutePath report-file)))

      :done)))

(comment
  (require '[shadow.cljs.devtools.api :as api])

  (-> (api/get-build-config :browser)
      (assoc-in [:compiler-options :source-map] true)
      (api/release* {})
      (extract-report-data)
      (tap>)
      )

  (generate
    (api/get-build-config :ui)
    {:report-file ".shadow-cljs/build-report/index.html"
     :inline false})
  )

(defmethod build-log/event->str ::report-to
  [{:keys [path] :as event}]
  (str "Wrote build report to: " path))

(defmethod build-log/event->str ::pseudo-names
  [event]
  (str "Skipped build report due to enabled :pseudo-names!"))

(defn hook
  {::build/stages #{:configure :flush}}
  ([build-state]
   (hook build-state {}))
  ([{::build/keys [stage mode]
     :as build-state}
    {:keys [output-to]
     :as opts}]

   (if-not (= :release mode)
     build-state

     (cond
       (= :configure stage)
       (if (get-in build-state [:compiler-options :source-map])
         build-state
         ;; force generating source maps if not enabled already, but don't link them
         (update build-state :compiler-options merge {:source-map true :source-map-comment false}))

       (get-in build-state [:compiler-options :pseudo-names])
       (do (build/log build-state {:type ::pseudo-names})
           build-state)

       :else
       (let [bundle-info
             (extract-report-data build-state)

             output-file
             (if (seq output-to)
               (io/file output-to)
               (data/output-file build-state "report.html"))]

         (generate-html build-state bundle-info output-file opts)

         (build/log build-state {:type ::report-to :path (.getAbsolutePath output-file)})

         build-state
         )))))

;; FIXME: parse args properly, need support for tag and output-dir
(defn -main [build-id report-file]
  (generate
    (api/get-build-config
      (keyword build-id))
    {:print-table true
     :report-file report-file}))


(comment
  (generate
    (api/get-build-config :browser)
    {:print-table true
     :report-file "tmp/report.html"}))