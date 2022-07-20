(ns shadow.css.analyzer-test
  (:require
    [clojure.pprint :refer (pprint)]
    [shadow.css.index :as index]
    [shadow.css.analyzer :as ana]
    [shadow.css.build :as build]
    [shadow.css.specs :as s]
    [clojure.test :as ct :refer (deftest is)]))

(deftest conform-invalid
  (pprint
    (s/conform
      '(css :px-4 invalid))))

(deftest conform-one
  (pprint
    (s/conform
      '(css :px-4 :my-2 :color/primary
         "pass"
         [:ui/md :px-6]
         [:ui/lg :px-8]
         ["&:hover" :color/secondary]))))


(deftest analyze-form
  (tap>
    (ana/process-form
      (build/start {})
      {:ns 'foo.bar
       :line 1
       :column 2
       :form '(css :px-4 :my-2
                "pass"
                [:ui/md :px-6]
                [:ui/lg :px-8
                 ["&:hover" :py-10]])})))


(deftest index-src-main
  (time
    (tap>
      (-> (index/create)
          (index/add-path "src/main" {})
          (index/write-to "src/dev/shadow-css-index.edn")))))

(deftest build-src-main
  (time
    (tap>
      (-> (build/start {})
          (build/generate '{:output-dir "tmp/css"
                            :chunks {:main {:include [shadow.cljs.ui.*]}}})))))