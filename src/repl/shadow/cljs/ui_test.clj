(ns shadow.cljs.ui-test
  (:require
    [clojure.test :refer (deftest is)]
    [clojure.pprint :refer (pprint)]
    [shadow.cljs.devtools.api :as api]
    [shadow.build.closure :as closure]
    [shadow.cljs.util :as util]
    [shadow.build.data :as data]
    [clojure.edn :as edn]
    [clojure.data.json :as json]
    [clojure.java.io :as io]))

(deftest test-bundle-info

  (let [report
        (api/generate-bundle-info :browser)]

    (pprint report)))


(deftest test-convert-to-webpack-stats
  (let [{:keys [build-sources] :as data}
        (-> (slurp ".shadow-cljs/builds/browser/release/bundle-info.edn")
            (edn/read-string))]

    (->> {:assets []
          :chunks []
          :modules
          (->> build-sources
               (map-indexed
                 (fn [idx {:keys [resource-name optimized-size js-size]}]
                   {:id idx
                    :identifier (str "/" resource-name)
                    :name (str "/" resource-name)
                    :size (or optimized-size js-size)
                    :reasons []}))
               (into []))}
         (json/write-str )
         (spit (io/file "tmp/stats.json")))))