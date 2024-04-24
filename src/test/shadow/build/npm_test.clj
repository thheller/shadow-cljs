(ns shadow.build.npm-test
  (:require
    [clojure.test :as ct :refer (deftest is)]
    [clojure.pprint :refer (pprint)]
    [clojure.java.io :as io]
    [shadow.build.npm :as npm]
    [shadow.build.resolve :refer (find-npm-resource)]
    [shadow.cljs.devtools.server.npm-deps :as npm-deps]
    [shadow.cljs.util :as util]
    [shadow.debug :as dbg]
    [clojure.edn :as edn])
  (:import [clojure.lang ExceptionInfo]))

(defmacro with-npm [[sym config] & body]
  `(let [~sym (npm/start (merge {:js-package-dirs ["test-env/node_modules"]} ~config))]
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

;; use test data in packages/resolve-check/tests.edn to check expected results
;; which are also verified by the :npm-resolve-check build and the created packages/resolve-check/run.js
;; which uses the enhanced-resolve package webpack uses, so the results should always match
(deftest test-enhanced-resolve-parity
  (let [tests (-> (io/file "packages" "resolve-check" "tests.edn")
                  (slurp)
                  (edn/read-string))]

    (doseq [{:keys [from request expected fail-expected] :as test} tests]
      (with-npm [x {}]
        (let [x
              (if (false? (:use-browser-overrides test))
                (assoc-in x [:js-options :use-browser-overrides] false)
                x)

              from-rc
              (when from
                (find-npm-resource x nil from))

              target-rc
              (find-npm-resource x from-rc request)]


          (cond
            fail-expected
            (if (nil? target-rc)
              (ct/do-report {:type :pass, :message (pr-str [:OK test])})
              (throw
                (ex-info "unexpected result, wasn't supposed to find anything but did"
                  {:test test
                   :target target-rc})))

            (false? expected)
            (if (identical? target-rc npm/empty-rc)
              (ct/do-report {:type :pass, :message (pr-str [:OK test])})
              (throw
                (ex-info "unexpected result, disabled/false require expected but got result"
                  {:test test
                   :target target-rc})))

            (string? expected)
            (let [file (:file target-rc)
                  expected-file
                  (when expected
                    (-> (io/file "test-env" expected)
                        (.getAbsoluteFile)))]

              (if (= file expected-file)
                (ct/do-report {:type :pass, :message (pr-str [:OK test])})
                (throw
                  (ex-info "unexpected result"
                    {:test test
                     :file file
                     :expected-file expected-file}))))
            :else
            (throw
              (ex-info "test with no expected?"
                {:test test
                 :file (:file target-rc)})))
          )))))

(deftest test-find-package-from-require
  (with-npm [x {}]
    (let [{:keys [package-name package-dir version] :as pkg-info}
          (npm/find-package-for-require x nil "pkg-a/doesnt/exist/but-that-doesnt-matter-for-this")]

      (is pkg-info)
      (is (= "pkg-a" package-name))
      (is (util/is-directory? package-dir))
      (is (contains? pkg-info :package-json))
      (is (= "1.0.0" version))
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

  (with-npm [x {:js-package-dirs ["test-env/node_modules" "test-env/node_modules/extra-js-package-dir"]}]
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
            (find-npm-resource x rc1 "./foo.css")))

      ;; can be configured to return empty instead of failing
      (let [{:keys [ns]}
            (find-npm-resource
              (assoc-in x [:js-options :ignore-asset-requires] true)
              rc1
              "./foo.css")]

        (is (= 'shadow$empty ns))
        ))))

;; enhanced-resolve doesn't have an option to opt out of this behavior
;; manual equiv to nested node_modules install test but disabled finding top level instead
(deftest test-nested-conflict-opt-out
  (with-npm [x {}]
    (let [test-root
          (-> (io/file "test-env" "node_modules")
              (.getAbsoluteFile))

          x
          (assoc-in x [:js-options :allow-nested-packages] false)

          rc1
          (find-npm-resource x nil "lvl1")

          {lvl2-file :file :as rc2}
          (find-npm-resource x rc1 "lvl2")]

      (is (= lvl2-file (io/file test-root "lvl2" "index.js")))
      (is (= "node_modules/lvl2/index.js" (:resource-name rc2)))
      )))


;; exports related things

(deftest test-exports-exact
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "exports")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/exports/foo.js" resource-name))
      )))

(deftest test-exports-exact2
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "exports/foo")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/exports/foo.js" resource-name))
      )))

(deftest test-exports-prefix
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "exports/prefix/foo.js")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/exports/other/foo.js" resource-name))
      )))

(deftest test-exports-prefix-missing
  (with-npm [x {}]
    (is (thrown? ExceptionInfo (find-npm-resource x nil "exports/prefix/bar.js")))
    ))

(deftest test-exports-wildcard
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "exports/wildcard/foo")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/exports/other/foo.js" resource-name))
      )))

(deftest test-exports-wildcard-with-suffix
  (with-npm [x {}]
    (let [{:keys [resource-name] :as rc1}
          (find-npm-resource x nil "exports/wildcard-with-suffix/foo.js")]

      (is rc1)
      (is (string? resource-name))
      (is (= "node_modules/exports/other/foo.js" resource-name))
      )))


(deftest test-exports-wildcard-missing
  (with-npm [x {}]
    (is (thrown? ExceptionInfo (find-npm-resource x nil "exports/wildcard/bar")))
    ))


(deftest test-exports-not-exported
  (with-npm [x {}]
    (is (thrown? ExceptionInfo (find-npm-resource x nil "exports/not-exported")))
    ))


(deftest test-dynamic-import
  (with-npm [x {}]
    (let [{:keys [js-dynamic-imports] :as rc} (find-npm-resource x nil "dyn-import")]
      (tap> rc)
      (is (= ["./dynamic.js"] js-dynamic-imports))
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

