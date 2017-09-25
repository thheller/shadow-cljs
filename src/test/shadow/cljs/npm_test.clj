(ns shadow.build.npm-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [clojure.java.io :as io]
            [shadow.build.npm :as npm]))

(defmacro with-npm [[sym config] & body]
  `(let [~sym (npm/start)]
     (try
       ~@body
       (finally
         (npm/stop ~sym))
       )))

(deftest test-package-info
  (with-npm [x {}]
    (let [{:keys [file package-name] :as shortid}
          (npm/find-resource x nil "shortid" {})

          pkg-info
          (npm/find-package x package-name)]

      (prn file)
      (pprint shortid)
      (pprint (dissoc pkg-info :package-json))
      )))

(deftest test-browser-overrides
  (with-npm [x {}]
    (let [index
          (-> (io/file "node_modules" "shortid" "lib" "index.js")
              (.getAbsoluteFile))

          _ (is (.exists index))

          rc
          (npm/find-resource x index "./util/cluster-worker-id" {:target :browser})]

      (pprint rc)

      )))

(deftest test-missing-package
  (with-npm [x {}]
    (let [rc
          (npm/find-resource x nil "i-dont-exist" {:target :browser})]

      (is (nil? rc))
      )))

(deftest test-resolve-to-global
  (with-npm [x {}]
    (let [rc
          (npm/find-resource x nil "react"
            {:target :browser
             :resolve
             {"react" {:target :global
                       :global "React"}}})]

      (pprint rc)
      )))

(deftest test-resolve-to-file
  (with-npm [x {}]
    (let [rc
          (npm/find-resource x nil "react"
            {:target :browser
             :mode :release
             :resolve
             {"react" {:target :file
                       :file "test/dummy/react.dev.js"
                       :file-min "test/dummy/react.min.js"}}})]

      (pprint rc)
      )))

(deftest test-resolve-to-other
  (with-npm [x {}]
    (let [rc
          (npm/find-resource x nil "react"
            {:target :browser
             :resolve
             {"react" {:target :npm
                       :require "preact"}}})]

      (pprint rc)
      )))


(deftest test-relative-file
  (with-npm [x {}]
    (let [file-info
          (npm/find-resource x (io/file ".") "./src/test/foo" {})]

      (pprint file-info)
      )))


(deftest test-file-info
  (with-npm [x {}]
    (let [file
          (-> (io/file "node_modules" "babel-runtime" "helpers" "typeof.js")
              (.getAbsoluteFile))

          rel-file
          (npm/find-relative x {:file file} "../core-js/symbol")

          file-info
          (npm/get-file-info* x file)]

      (pprint file-info)
      )))

(deftest test-package
  (with-npm [x {}]
    (pprint (npm/find-package* x "react"))
    ))