(ns shadow.cljs.classpath-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [clojure.java.io :as io]
            [shadow.build.classpath :as cp]))

(comment
  (stop x)

  (get-classpath-entries x)

  (find-resources x (io/file "src" "dev"))
  (find-resources x (io/file "/Users/zilence/.m2/repository/thheller/shadow-client/1.0.20170628/shadow-client-1.0.20170628.jar"))


  (pprint *1)

  (-> (io/file "tmp/shadow") (.getAbsoluteFile) (.toPath) (.normalize))

  )

(deftest test-find-resources-in-path
  (let [x (cp/find-fs-resources* {} (io/file "src" "dev"))]
    (pprint x)
    ))

(deftest test-classpath-filtering
  (let [x (cp/start (io/file "tmp" "classpath-cache"))]
    (doseq [e (cp/get-classpath-entries x)
            :when (.isDirectory e)]
      (prn e))
    ))

(deftest test-classpath-dir
  (let [x
        (cp/start (io/file "target" "shadow-cljs"))

        source-path
        (io/file "test" "resource-dir")

        {:keys [externs resources foreign-libs] :as contents}
        (cp/find-fs-resources x source-path)

        src-file
        (io/file source-path "foo" "bar.cljs")]

    (is (= 1 (count externs)))
    (is (= 1 (count foreign-libs)))
    (is (= 6 (count resources)))
    (pprint resources)
    ))

(deftest test-classpath-index
  (let [x
        (cp/start (io/file "target" "shadow-cljs"))

        source-path
        (-> (io/file "test" "resource-dir")
            (.getAbsoluteFile))

        src-file
        (io/file source-path "foo" "bar.cljs")]

    (cp/index-classpath x [source-path])

    (cp/file-update x source-path src-file)
    ;; (pprint @(:index-ref x))

    (cp/file-remove x source-path src-file)
    ;; (pprint @(:index-ref x))

    (cp/file-add x source-path src-file)
    ;; (pprint @(:index-ref x))
    ))

(deftest test-classpath-jar
  (let [x
        (cp/start (io/file "target" "shadow-cljs"))

        {:keys [externs resources foreign-libs] :as contents}
        (cp/find-jar-resources x (io/file "test" "resource.jar"))]

    (is (= 1 (count externs)))
    (is (= 1 (count foreign-libs)))
    (is (= 2 (count resources)))
    (pprint contents)
    ))


(deftest test-and-init
  (let [x (-> (cp/start (io/file "target" "classpath-test"))
              ;; (cp/index-classpath* (io/file "src" "dev"))
              (cp/index-classpath
                [(io/file "test" "resource-dir")]))]

    (pprint @(:index-ref x))
    ))

(deftest test-all
  (let [{:keys [index-ref] :as x}
        (-> (cp/start (io/file "target" "classpath-test"))
              (cp/index-classpath))

        y
        (get-in @index-ref [:sources "shadow/js.js"])]
    (pprint y)
    #_(doseq [[path externs] (cp/get-deps-externs x)
              {:keys [resource-name url] :as ext} externs]
        (prn [:ext resource-name url]))
    ))


(deftest test-specific-package
  (let [x (-> (cp/start (io/file "target" "classpath-test"))
              ;; (cp/index-classpath* (io/file "src" "dev"))
              (cp/index-classpath
                [(io/file "/Users/zilence/.m2/repository/cljsjs/react/15.6.1-1/react-15.6.1-1.jar")]))]

    (pprint @(:index-ref x))
    ))