(ns shadow.cljs.release-snapshot-test
  (:require
    [clojure.test :refer (deftest is)]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [shadow.cljs.devtools.release-snapshot :as snap]))


(deftest test-print-table
  (let [bundle-info
        (-> (io/file ".shadow-cljs" "release-snapshots" "browser" "latest" "bundle-info.edn")
            (slurp)
            (edn/read-string))]

    (snap/print-bundle-info-table bundle-info {:group-jar true
                                               :group-npm true})
    #_(snap/print-bundle-info-table bundle-info {:group-jar false
                                                 :group-npm true})
    #_(snap/print-bundle-info-table bundle-info {:group-jar true
                                                 :group-npm false})

    ))
