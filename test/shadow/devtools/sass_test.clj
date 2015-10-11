(ns shadow.sass-test
  (:use clojure.test)
  (:require [clojure.pprint :refer (pprint)]
            [shadow.sass :as sass]
            [clojure.java.io :as io]
            ))

(def css-dir (io/file "cljs-data" "css"))

(deftest test-build-once
  (sass/build-module (io/file css-dir "mod-a.scss")
                     (io/file "tmp" "out.css")))

(deftest test-build-all
  (pprint (sass/build-all css-dir (io/file "tmp"))))

(deftest test-build-specific
  (let [now (System/currentTimeMillis)]
    (sass/build-with-manifest
      [(io/file css-dir "mod-a.scss")]
      (io/file "tmp")
      (fn [name]
        (str now "-" name))
      )))
