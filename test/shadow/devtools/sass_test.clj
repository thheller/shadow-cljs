(ns shadow.devtools.sass-test
  (:use clojure.test)
  (:require [clojure.pprint :refer (pprint)]
            [shadow.devtools.sass :as sass]
            [clojure.java.io :as io]
            ))

(def css-dir
  (io/file "test-css"))

(def out-dir
  (io/file "target/test-css-out/out-dir"))

(def css-package
  {:name "test"
   :modules [(io/file "test-css/mod-a.scss")
             (io/file "test-css/mod-b.scss")]
   :public-dir (io/file "target/test-css-out/css")
   :public-path "css"})

(deftest test-build-module
  (let [source (io/file css-dir "mod-a.scss")
        target (io/file out-dir "out.css")]
    (sass/build-module source target)
    (println (slurp target))))

(deftest test-build-package
  (pprint (sass/build-package css-package)))

(deftest test-build-package-with-rename
  (let [now (System/currentTimeMillis)]
    (pprint (sass/build-package (assoc css-package
                                  :rename-fn (fn [name]
                                               (str now "-" name)))))))
