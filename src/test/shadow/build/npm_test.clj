(ns shadow.build.npm-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [clojure.java.io :as io]
            [shadow.build.npm :as npm]
            [shadow.build.resolve :refer (find-npm-resource)]
            [shadow.cljs.devtools.server.npm-deps :as npm-deps]
            [shadow.cljs.util :as util])
  (:import [clojure.lang ExceptionInfo]))

(defmacro with-npm [[sym config] & body]
  `(let [~sym (npm/start (merge {:js-package-dirs ["test-env"]} ~config))]
     (try
       ~@body
       (finally
         (npm/stop ~sym))
       )))

(deftest test-missing-package-returns-nil
  (with-npm [x {}]
    (let [rc (find-npm-resource x nil "i-dont-exist")]
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
          (find-npm-resource x nil "pkg-a")]

      ;; has browser override for index -> index-browser
      (is rc)
      (is (string? resource-name))
      (is (= 'module$node_modules$pkg_a$index_browser ns))
      (is (= "node_modules/pkg-a/index-browser.js" resource-name))
      )))

(deftest test-nested-browser-override
  (with-npm [x {}]
    (let [{:keys [file] :as require-from-rc}
          (find-npm-resource x nil "pkg-nested-override/dir/bar")

          ;; emulate require("./foo") from bar.js
          {:keys [resource-name ns file package-name] :as rc}
          (find-npm-resource x file "./foo")]

      (is rc)
      (is (string? resource-name))
      (is (= 'module$node_modules$pkg_nested_override$dir$foo_browser ns))
      (is (= "node_modules/pkg-nested-override/dir/foo.browser.js" resource-name))
      )))

(deftest test-nested-pkg-override
  (with-npm [x {}]
    (let [{:keys [file] :as require-from-rc}
          (find-npm-resource x nil "pkg-nested-override/dir/bar")

          ;; emulate require("pkg") when browser { "pkg" : "./pkg-override.js" }
          {:keys [resource-name ns file package-name] :as rc}
          (find-npm-resource x file "pkg")]

      (is rc)
      (is (string? resource-name))
      (is (= 'module$node_modules$pkg_nested_override$pkg_override ns))
      (is (= "node_modules/pkg-nested-override/pkg-override.js" resource-name))
      )))

(deftest test-browser-override-npm-package
  (with-npm [x {}]
    (let [{:keys [file] :as require-from-rc}
          (find-npm-resource x nil "pkg-nested-override/dir/bar")

          ;; emulate require("fs") in file
          ;; should end up serving the empty rc because fs can't be loaded in the browser
          {:keys [resource-name ns] :as rc}
          (find-npm-resource x file "fs")]

      (is rc)
      (is (string? resource-name))
      (is (= 'shadow$empty ns))
      (is (= "shadow$empty.js" resource-name))
      )))

(deftest test-no-browser-override
  (with-npm [x {}]
    (let [{:keys [resource-name ns file package-name] :as rc}
          (find-npm-resource
            (update x :js-options merge {:use-browser-overrides false})
            nil
            "pkg-a")]

      ;; has browser override for index -> index-browser
      (is rc)
      (is (string? resource-name))
      (is (= 'module$node_modules$pkg_a$index ns))
      (is (= "node_modules/pkg-a/index.js" resource-name))
      )))

(deftest test-resolve-to-global
  (with-npm [x {}]
    (let [{:keys [ns type requires source] :as rc}
          (find-npm-resource
            (update x :js-options merge {:resolve
                                         {"react" {:target :global
                                                   :global "React"}}})
            nil
            "react")]

      (is rc)
      (is (= 'module$react ns))
      (is (= :js type))
      (is (= "module.exports=(React);" source))
      (is (= #{} requires))
      )))

(deftest test-resolve-to-file
  (with-npm [x {}]
    (let [{:keys [ns resource-name] :as rc}
          (find-npm-resource
            (update x :js-options merge {:mode :release
                                         :resolve
                                         {"react" {:target :file
                                                   :file "test/dummy/react.dev.js"
                                                   :file-min "test/dummy/react.min.js"}}})
            nil
            "react")]

      (is rc)
      (is (= 'module$test$dummy$react_min ns))
      )))

(deftest test-resolve-to-other
  (with-npm [x {}]
    (let [{:keys [ns resource-name] :as rc}
          (find-npm-resource
            (update x :js-options merge {:resolve
                                         {"react" {:target :npm
                                                   :require "pkg-a"}}})
            nil
            "react")]

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
            "./src/test/foo")]

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
    (let [{:keys [file] :as rc1}
          (find-npm-resource x nil "pkg-a/nested/thing")

          {:keys [ns resource-name] :as rc2}
          (find-npm-resource x file "..")]

      (is rc1)
      (is rc2)
      (is (string? resource-name))
      (is (= 'module$node_modules$pkg_a$index_browser ns))
      (is (= "node_modules/pkg-a/index-browser.js" resource-name))
      )))


(deftest test-require-file-over-dir
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "file-over-dir/foo")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/file-over-dir/foo.js" resource-name))
      )))

(deftest test-require-file-over-dir-with-ext
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "file-over-dir/foo.js")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/file-over-dir/foo.js" resource-name))
      )))

(deftest test-require-entry-dir-with-index
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "entry-dir/foo")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/entry-dir/foo/index.js" resource-name))
      )))

(deftest test-main-as-dir
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "main-is-dir")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/main-is-dir/lib/index.js" resource-name))
      )))

(deftest test-dir-dot-js
  (with-npm [x {}]
    ;; it should never try to pick dir.js
    ;; node has asn1 and asn1.js packages
    ;; and it should not pick the .js version over the other
    (let [rc1 (find-npm-resource x nil "dir")]
      (is (nil? rc1))
      )))

(deftest test-nested-pkg
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "nested-pkg/nested")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/nested-pkg/nested/main.js" resource-name))
      )))

(deftest test-nested-pkg-from-relative
  (with-npm [x {}]
    (let [file
          (io/file "test-env" "nested-pkg" "relative" "index.js")

          {:keys [resource-name] :as rc1}
          (find-npm-resource x file "../nested")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/nested-pkg/nested/main.js" resource-name))
      )))

(deftest test-nested-pkg-without-main
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "nested-pkg/just-index")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/nested-pkg/just-index/index.js" resource-name))
      )))

;; the node docs made it appear like @scoped things were special but it turns
;; out they are just like any other nested pkg with special rules only for the registry
(deftest test-scoped-pkg
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "@scoped/a")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/@scoped/a/index.js" resource-name))
      )))

(deftest test-node-implicits
  (with-npm [x {}]
    (let [{:keys [uses-global-buffer uses-global-process deps] :as rc1}
          (find-npm-resource x nil "implicits")]

      (is uses-global-buffer)
      (is uses-global-process)
      (is (= ["buffer" "process"] deps)))))

(deftest test-extra-js-package-dir
  (with-npm [x {}]
    (let [rc1 (find-npm-resource x nil "extra-package")]
      (is (nil? rc1))))

  (with-npm [x {:js-package-dirs ["test-env" "test-env/extra-js-package-dir"]}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "extra-package")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/extra-package/index.js" resource-name))
      )))

(deftest test-asset-require
  (with-npm [x {}]
    (let [{:keys [file] :as rc1}
          (find-npm-resource x nil "with-assets/index.js")]

      (is (thrown-with-msg? ExceptionInfo #"failed to inspect"
            (find-npm-resource x file "./foo.css")))

      ;; can be configured to return empty instead of failing
      (let [{:keys [ns]}
            (find-npm-resource
              (assoc-in x [:js-options :ignore-asset-requires] true)
              file
              "./foo.css")]

        (is (= 'shadow$empty ns))
        ))))

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

