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
    (let [file
          (npm/find-require x nil "react")

          {:keys [package-name] :as file-info}
          (npm/get-file-info x file)

          pkg-info
          (npm/find-package x package-name)]

      (prn file)
      (pprint file-info)
      (pprint pkg-info)
      )))

(deftest test-relative-file
  (with-npm [x {}]
    (let [file
          (npm/find-require x (io/file ".") "./src/test/foo")

          file-info
          (npm/get-file-info* x file)]

      (pprint file-info)
      )))


(deftest test-file-info
  (with-npm [x {}]
    (let [file
          (-> (io/file "node_modules" "babel-runtime" "helpers" "typeof.js")
              (.getAbsoluteFile))

          rel-file
          (npm/find-relative x file "../core-js/symbol")

          file-info
          (npm/get-file-info* x file)]

      (pprint file-info)
      )))

(deftest test-package
  (with-npm [x {}]
    (pprint (npm/find-package* x "react"))
    ))