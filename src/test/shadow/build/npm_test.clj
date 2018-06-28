(ns shadow.build.npm-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [clojure.java.io :as io]
            [shadow.build.npm :as npm]
            [shadow.build.resolve :refer (find-npm-resource)]
            [shadow.cljs.devtools.server.npm-deps :as npm-deps]
            [shadow.cljs.util :as util]))

(defmacro with-npm [[sym config] & body]
  `(let [~sym (npm/start (merge {:node-modules-dir "test-env"} ~config))]
     (try
       ~@body
       (finally
         (npm/stop ~sym))
       )))

(deftest test-missing-package-returns-nil
  (with-npm [x {}]
    (let [rc (find-npm-resource x nil "i-dont-exist" {})]
      (is (nil? rc))
      )))

(deftest test-package-info
  (with-npm [x {}]
    (let [{:keys [package-name package-dir version] :as pkg-info}
          (npm/find-package x "pkg-a")]

      (is pkg-info)
      (is (= "pkg-a" package-name))
      (is (util/is-directory? package-dir))
      (is (contains? pkg-info :package-json))
      (is (= "1.0.0" version))
      )))

(deftest test-browser-override
  (with-npm [x {}]
    (let [{:keys [resource-name ns file package-name] :as rc}
          (find-npm-resource x nil "pkg-a" {})]

      ;; has browser override for index -> index-browser
      (is rc)
      (is (string? resource-name))
      (is (= 'module$node_modules$pkg_a$index_browser ns))
      (is (= "node_modules/pkg-a/index-browser.js" resource-name))
      )))

(comment (deftest test-babel-transform
           (with-npm [x {}]

             (let [source "var foo = 1; export { foo };"]
               (pprint (npm/babel-convert-source x nil source))
               ))))

(deftest test-resolve-to-global
  (with-npm [x {}]
    (let [{:keys [ns type requires source] :as rc}
          (find-npm-resource x nil "react"
            {:target :browser
             :resolve
             {"react" {:target :global
                       :global "React"}}})]

      (is rc)
      (is (= 'module$react ns))
      (is (= :js type))
      (is (= "module.exports=(React);" source))
      (is (= #{} requires))
      )))

(deftest test-resolve-to-file
  (with-npm [x {}]
    (let [{:keys [ns resource-name] :as rc}
          (find-npm-resource x nil "react"
            {:mode :release
             :resolve
             {"react" {:target :file
                       :file "test/dummy/react.dev.js"
                       :file-min "test/dummy/react.min.js"}}})]

      (is rc)
      (is (= 'module$test$dummy$react_min ns))
      )))

(deftest test-resolve-to-other
  (with-npm [x {}]
    (let [{:keys [ns resource-name] :as rc}
          (find-npm-resource x nil "react"
            {:target :browser
             :resolve
             {"react" {:target :npm
                       :require "pkg-a"}}})]

      (is rc)
      (is (string? resource-name))
      (is (= 'module$node_modules$pkg_a$index_browser ns))
      (is (= "node_modules/pkg-a/index-browser.js" resource-name))
      )))


(deftest test-relative-file
  (with-npm [x {}]
    (let [{:keys [ns resource-name] :as rc}
          (find-npm-resource x
            (-> (io/file ".")
                (.getCanonicalFile))
            "./src/test/foo" {})]

      (is rc)
      (is (= 'module$src$test$foo ns))
      (is (= "src/test/foo.js" resource-name))
      )))


(deftest test-file-info-direct
  (with-npm [x {}]
    (let [{:keys [ns resource-name] :as rc}
          (npm/get-file-info x
            (-> (io/file "src" "test" "foo.js")
                (.getCanonicalFile)))]

      (is rc)
      (is (= 'module$src$test$foo ns))
      (is (= "src/test/foo.js" resource-name))
      )))


(deftest test-require-dot-dot
  (with-npm [x {}]
    (let [ctx {}

          {:keys [file] :as rc1}
          (find-npm-resource x nil "pkg-a/nested/thing" ctx)

          {:keys [ns resource-name] :as rc2}
          (find-npm-resource x file ".." ctx)]

      (is rc1)
      (is rc2)
      (is (string? resource-name))
      (is (= 'module$node_modules$pkg_a$index_browser ns))
      (is (= "node_modules/pkg-a/index-browser.js" resource-name))
      )))


(deftest test-require-file-over-dir
  (with-npm [x {}]
    (let [ctx {}

          {:keys [resource-name] :as rc1}
          (find-npm-resource x nil "file-over-dir/foo" ctx)]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/file-over-dir/foo.js" resource-name))
      )))

(deftest test-require-file-over-dir-with-ext
  (with-npm [x {}]
    (let [ctx {}

          {:keys [resource-name] :as rc1}
          (find-npm-resource x nil "file-over-dir/foo.js" ctx)]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/file-over-dir/foo.js" resource-name))
      )))

(deftest test-require-entry-dir-with-index
  (with-npm [x {}]
    (let [ctx {}

          {:keys [resource-name] :as rc1}
          (find-npm-resource x nil "entry-dir/foo" ctx)]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/entry-dir/foo/index.js" resource-name))
      )))

(deftest test-main-as-dir
  (with-npm [x {}]
    (let [ctx {}

          {:keys [resource-name] :as rc1}
          (find-npm-resource x nil "main-is-dir" ctx)]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/main-is-dir/lib/index.js" resource-name))
      )))

(deftest test-nested-pkg
  (with-npm [x {}]
    (let [ctx {}

          {:keys [resource-name] :as rc1}
          (find-npm-resource x nil "nested-pkg/nested" ctx)]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/nested-pkg/nested/main.js" resource-name))
      )))

(deftest test-nested-pkg-without-main
  (with-npm [x {}]
    (let [ctx {}

          {:keys [resource-name] :as rc1}
          (find-npm-resource x nil "nested-pkg/just-index" ctx)]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/nested-pkg/just-index/index.js" resource-name))
      )))

;; the node docs made it appear like @scoped things were special but it turns
;; out they are just like any other nested pkg with special rules only for the registry
(deftest test-scoped-pkg
  (with-npm [x {}]
    (let [ctx {}

          {:keys [resource-name] :as rc1}
          (find-npm-resource x nil "@scoped/a" ctx)]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/@scoped/a/index.js" resource-name))
      )))

(comment
  ;; FIXME: write proper tests for these

  (let [deps (npm-deps/get-deps-from-classpath)]
    (prn deps))

  (npm-deps/resolve-conflicts
    [{:id "react" :version ">=15.0.0" :url "a"}
     {:id "react" :version "^16.0.0" :url "b"}])

  (npm-deps/resolve-conflicts
    [{:id "react" :version ">=16.0.0" :url "a"}
     {:id "react" :version ">=16.1.0" :url "b"}])

  (let [pkg {"dependencies" {"react" "^16.0.0"}}]
    (npm-deps/is-installed? {:id "react" :version "^15.0.0" :url "a"} pkg))

  (install-deps
    {:node-modules {:managed-by :yarn}}
    [{:id "react" :version "^16.0.0"}
     {:id "react-dom" :version "^16.0.0"}]))

