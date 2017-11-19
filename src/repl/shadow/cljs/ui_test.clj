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
    [clojure.java.shell :refer (sh)]
    [clojure.java.io :as io]
    [shadow.build.output :as output]
    [shadow.build :as build]
    [shadow.build.api :as build-api])
  (:import (java.io FileOutputStream)))

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
         (json/write-str)
         (spit (io/file "tmp/stats.json")))))

(defn flush-bundle-info [state]
  (let [bundle-info
        (output/generate-bundle-info state)

        bundle-file
        (data/cache-file state "bundle-info.edn")]

    (io/make-parents bundle-file)

    (spit bundle-file
      (with-out-str
        (pprint bundle-info))))

  state)

(deftest test-release-info
  (api/release-snapshot :browser {}))