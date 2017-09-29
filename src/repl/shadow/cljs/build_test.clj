(ns shadow.build-test
  (:require
    [clojure.test :as test :refer (is deftest)]
    [shadow.build.api :as build-api]
    [shadow.cljs.repl :as repl]
    [shadow.build.node :as node]
    [shadow.cljs.util :as util]
    [shadow.build.cache :as cache]
    [cljs.analyzer :as ana]
    [clojure.pprint :refer (pprint)]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.repl :refer (pst)]
    [cljs.analyzer :as a]
    [clojure.set :as set]
    [cljs.closure :as cljs-closure]
    [cljs.analyzer.api :as ana-api]
    [shadow.build.output :as output]
    [shadow.build.ns-form :as ns-form]
    [shadow.build :as comp]
    [shadow.cljs.devtools.api :as devtools-api]
    [shadow.build.targets.browser :as browser]
    [shadow.build.closure :as closure]
    [cljs.compiler :as cljs-comp])
  (:import (java.util.regex Pattern)
           (java.io File ByteArrayInputStream)
           (java.net URL)
           (com.google.javascript.jscomp ClosureCodingConvention CompilerOptions SourceFile JSModuleGraph JSModule)
           (clojure.lang ExceptionInfo)))


;; FIXME: these are all pretty much outdated, some are covered by other namespaces now
(comment

  (deftest test-initial-scan
    (.setLastModified (io/file "dev/shadow/test_macro.clj") 0)
    (let [state (-> (api/init-state)
                    (api/find-resources-in-classpath)
                    (api/find-resources "test-data")
                    (api/finalize-config))]
      (is (empty? (api/scan-for-modified-files state)))
      (.setLastModified (io/file "dev/shadow/test_macro.clj") (System/currentTimeMillis))
      (let [modded (api/scan-for-modified-files state)
            state (api/reload-modified-files! state modded)]
        (is (empty? (api/scan-for-modified-files state)))
        )))

  ;; this needs real testing

  (deftest test-js-env
    (let [state (-> (api/init-state)
                    (api/enable-source-maps)
                    (api/find-resources-in-classpath)
                    (api/find-resources "cljs-data/dummy/src")
                    ;; (api/step-find-resources "/Users/zilence/code/oss/closure-library/closure")
                    ;; (api/step-find-resources "/Users/zilence/code/oss/closure-library/third_party")
                    (assoc :optimizations :advanced
                      :pretty-print false
                      :work-dir (io/file "target/cljs-work")
                      :cache-dir (io/file "target/cljs-cache")
                      :cache-level :jars
                      :output-dir (io/file "target/cljs")
                      :pseudo-names true
                      :pretty-print true
                      :asset-path "target/cljs")
                    (api/finalize-config)
                    (api/configure-module :loader ['goog.module.ModuleManager] #{})
                    ;; (api/step-configure-module :cljs ['cljs.core] #{:loader})
                    ;; (api/step-configure-module :basic ['basic] #{:cljs})
                    ;; (api/step-configure-module :other ['other] #{:cljs})
                    (api/compile-modules)
                    ;;(api/flush-unoptimized)
                    (api/closure-optimize)
                    (api/flush-modules-to-disk)
                    ;;(api/step-configure-module :cljs ['cljs.core] #{})
                    ;;(api/step-configure-module :page ['page] #{:cljs})
                    ;;(api/step-configure-module :worker1 ['worker1] #{:cljs} {:web-worker true})
                    ;;(api/step-configure-module :worker2 ['worker2] #{:cljs} {:web-worker true})
                    )]

      ))

  (deftest test-reloading
    (let [file-a (io/file "target/reload-test/test_a.cljs")
          file-b (io/file "target/reload-test/test_b.cljs")
          foo-fn "(defn ^:export foo[] :bar)"]
      (io/make-parents file-a)

      (doseq [file [file-a file-b]
              :when (.exists file)]
        (.delete file))

      (spit file-a (str/join "\n" ["(ns test-a)"
                                   foo-fn]))

      (let [state (-> (api/init-state)
                      (api/enable-source-maps)
                      (api/find-resources-in-classpath)
                      (api/find-resources "target/reload-test")
                      (assoc :optimizations :whitespace
                        :pretty-print true
                        :work-dir (io/file "target/cljs-work")
                        :output-dir (io/file "target/cljs")
                        :asset-path "target/cljs")
                      (api/finalize-config)
                      (api/configure-module :test
                        ['test-a
                         'shadow.ns-on-classpath]
                        #{}))]

        (is (nil? (get-in state [:sources "test_b.cljs"])))

        (api/compile-modules state) ;; no error is good enough for now


        ;; wait for a bit
        ;; otherwise the spit may end up in the same millisec as the previous one
        ;; which wait-and-reload can't detect
        (Thread/sleep 50)

        (prn [:before (.lastModified file-a)])
        ;; now we modify it to depend on test-b
        (spit file-a (str/join "\n" ["(ns test-a (:require [test-b]))"
                                     foo-fn]))


        (Thread/sleep 50)
        (.setLastModified file-a (System/currentTimeMillis))
        ;; FIXME: last modified somehow broken ... same as before ... sometimes ...
        (prn [:after (.lastModified (io/file "target/reload-test/test_a.cljs"))])

        (.setLastModified (io/file "src/dev/shadow/ns_on_classpath.cljs") (System/currentTimeMillis))

        (let [modified (api/scan-for-modified-files state)
              new (api/scan-for-new-files state)]

          (prn [:modified (map :url modified)])
          (is (empty? new))
          (is (= 1 (count modified)))
          (is (= :modified (-> modified first :scan)))

          ;; empty file is :new but cannot be compiled, should produce warning, hard to test
          (spit file-b "")

          (let [state (api/reload-modified-files! state modified)
                new (api/scan-for-new-files state)
                modified (api/scan-for-modified-files state)]

            (is (empty? modified))
            (is (= 1 (count new)))

            (spit file-b (str/join "\n" ["(ns test-b)"
                                         foo-fn]))

            (let [new (api/scan-for-new-files state)
                  state (api/reload-modified-files! state new)]
              (is (= 1 (count new)))

              ;; FIXME: test if everything is ok, no exception is good enough for now
              (api/compile-modules state)
              ))))))



  (deftest test-caching
    (let [do-build
          (fn []
            (-> (api/init-state)
                (api/enable-source-maps)
                (assoc :optimizations :none
                  :pretty-print false
                  :work-dir (io/file "target/cljs-work")
                  :cache-dir (io/file "target/cljs-cache")
                  :output-dir (io/file "target/cljs")
                  :asset-path "target/cljs")
                (api/find-resources-in-classpath)
                (api/find-resources "cljs-data/dummy/src")
                (api/finalize-config)
                (api/configure-module :basic ['basic] #{})
                (api/compile-modules)
                (api/flush-unoptimized)))]
      (println "--- ROUND 1")
      (.setLastModified (io/file "cljs-data/dummy/src/common.cljs") 1)
      (do-build)
      ;; all files should be cached now

      (println "--- ROUND 2")
      ;; should load only cached
      (do-build)

      (println "--- ROUND 3")
      ;; touch one file which should cause a recompile of all dependents too
      (.setLastModified (io/file "cljs-data/dummy/src/common.cljs") (System/currentTimeMillis))
      (do-build)

      ;; FIXME: checkout output that basic and common were recompiled, not just common
      ))


  (deftest test-caching-with-jars
    (let [do-build
          (fn [jar-path]
            (-> (api/init-state)
                (api/enable-source-maps)
                (assoc :optimizations :none
                  :pretty-print false
                  :work-dir (io/file "target/cljs-work")
                  :cache-dir (io/file "target/cljs-cache")
                  :output-dir (io/file "target/cljs")
                  :asset-path "target/cljs")
                (api/find-resources-in-classpath)
                (api/find-resources "cljs-data/dummy/src")
                (api/find-resources jar-path)
                (api/finalize-config)
                (api/configure-module :basic ['basic
                                              'hello-world] #{})
                (api/compile-modules)
                (api/flush-unoptimized)))]

      (println "--- ROUND 1")
      (do-build "cljs-data/dummy/lib/hello-world-v1.jar")
      ;; all files should be cached now

      (println "--- ROUND 2")
      ;; should load only cached
      (do-build "cljs-data/dummy/lib/hello-world-v1.jar")

      (println "--- ROUND 3")
      ;; v2 is older than our previous build, but must still trigger a recompile
      (do-build "cljs-data/dummy/lib/hello-world-v2.jar")
      ))

  (deftest test-optimized-build
    (-> (api/init-state)
        (api/enable-source-maps)
        (assoc :optimizations :advanced
          :pretty-print false
          :work-dir (io/file "target/cljs-work")
          :cache-dir (io/file "cljs-data/foreign/out/cljs-cache")
          :output-dir (io/file "cljs-data/foreign/out")
          :asset-path "out")
        (api/find-resources-in-classpath)
        (api/find-resources "cljs-data/foreign/src")
        (api/add-foreign "jquery.js"
          '#{jquery}
          #{}
          (slurp (io/file "cljs-data/foreign/lib/jquery-2.1.3.min.js"))
          (slurp (io/file "cljs-data/foreign/lib/jquery.externs.js")))
        (api/finalize-config)
        (api/configure-module :test ['wants-jquery] #{})
        (api/compile-modules)
        ;; (api/closure-optimize)
        ;; (api/flush-modules-to-disk)
        (api/flush-unoptimized)
        ))

  (deftest test-dummy
    (let [s (-> (api/init-state)
                (assoc :optimizations :none
                  :pretty-print true
                  :cache-level :jars
                  :work-dir (io/file "target/test-cljs-work")
                  :output-dir (io/file "target/test-cljs")
                  :asset-path "target/test-cljs")
                (api/find-resources-in-classpath)
                (api/find-resources "cljs-data/dummy/src")
                (api/finalize-config)
                (api/configure-module :test ['cljs.repl] #{})
                (api/compile-modules)
                ;; (api/closure-optimize)
                ;; (api/flush-modules-to-disk)
                (api/flush-unoptimized)
                )]
      ;; (println (get-in s [:sources "api/repl.cljs" :output]))
      ))

  (deftest test-ns-with-use
    (let [s (-> (api/init-state)
                (assoc :optimizations :none
                  :pretty-print true
                  :work-dir (io/file "target/test-cljs-work")
                  :output-dir (io/file "target/test-cljs")
                  :asset-path "target/test-cljs")
                (api/find-resources-in-classpath)
                (api/find-resources "cljs-data/dummy/src")
                (api/configure-module :test ['with-use] #{})
                (api/finalize-config)
                (api/compile-modules)
                ;; (api/closure-optimize)
                ;; (api/flush-modules-to-disk)
                ;;(api/flush-unoptimized)
                )]
      (println (get-in s [:sources "with_use.cljs" :output]))))

  (deftest test-ns-with-require-macros-refer
    (let [s (-> (api/init-state)
                (assoc :cache-level :off)
                (api/find-resources-in-classpath)
                (api/configure-module :test ['shadow.macro-require-test] #{})
                (api/compile-modules)
                ;; (api/closure-optimize)
                ;; (api/flush-modules-to-disk)
                ;;(api/flush-unoptimized)
                )]
      ))

  (deftest test-ns-with-rename
    (let [s (-> (api/init-state)
                (assoc :optimizations :advanced
                  :pretty-print true
                  :cache-level :jars
                  :work-dir (io/file "target/test-cljs-work")
                  :output-dir (io/file "target/test-cljs")
                  :asset-path "target/test-cljs")
                (api/find-resources-in-classpath)
                (api/find-resources "cljs-data/dummy/src")
                (api/configure-module :test ['with-rename] #{})
                (api/finalize-config)
                (api/compile-modules)
                ;; (api/closure-optimize)
                ;; (api/flush-modules-to-disk)
                (api/flush-unoptimized)
                )]

      (println (get-in s [:sources "with_rename.cljs" :output]))
      ))

  (deftest test-bad-files
    (let [state
          (-> (api/init-state)
              (api/find-resources-in-classpath)
              ;; this should not fail, although there are broken files
              (api/find-resources "cljs-data/bad/src")
              (api/finalize-config)
              (api/configure-module :test ['bad-ns] #{}))]

      (is (thrown? clojure.lang.ExceptionInfo (api/compile-modules state)))
      ))

  (deftest test-bad-jar
    (let [s (-> (api/init-state)
                (assoc :optimizations :none
                  :pretty-print true
                  :work-dir (io/file "target/test-cljs-work")
                  :output-dir (io/file "target/test-cljs")
                  :asset-path "target/test-cljs")
                (api/find-resources "/Users/zilence/.m2/repository/org/omapi/om/1.0.0-alpha12/om-1.0.0-alpha12.jar")
                (api/find-resources "cljs-data/dummy/src")
                (api/finalize-config)
                )]
      (println (get-in s [:sources "api/repl.cljc" :output]))))

  (deftest test-macro-reloading
    (let [s (-> (api/init-state)
                (assoc :optimizations :none
                  :pretty-print true
                  :work-dir (io/file "target/test-cljs-work")
                  :output-dir (io/file "target/test-cljs")
                  :asset-path "target/test-cljs")
                (api/find-resources-in-classpath)
                (api/find-resources "cljs-data/dummy/src")
                (api/finalize-config)
                (api/configure-module :test ['basic] #{})
                ;; (api/compile-modules)
                ;; (api/closure-optimize)
                ;; (api/flush-modules-to-disk)
                ;;(api/flush-unoptimized)
                )]

      (pprint (api/find-resources-using-macro s 'shadow.test-macro))
      ))

  (deftest test-find-dependents
    (let [s (-> (api/init-state)
                (assoc :optimizations :none
                  :output-dir (io/file "target/test-cljs")
                  :asset-path "target/test-cljs")
                (api/find-resources-in-classpath)
                (api/find-resources "cljs-data/dummy/src")
                (api/finalize-config)
                (api/configure-module :test ['basic] #{})
                ;; (api/compile-modules)
                ;; (api/closure-optimize)
                ;; (api/flush-modules-to-disk)
                ;;(api/flush-unoptimized)
                )]

      (pprint (api/find-dependent-names s 'common))
      (pprint (api/find-dependents-for-names s ["common.cljs"]))

      ))

  (deftest test-compiler-error
    (let [s (-> (api/init-state)
                (api/find-resources-in-classpath)
                (api/find-resources "cljs-data/dummy/src")
                (api/finalize-config)
                (api/configure-module :test ['broken] #{}))]
      (try
        (api/compile-modules s)
        (catch Exception e
          (is (instance? ExceptionInfo e))
          (let [data (ex-data e)]
            (is (= :reader-exception (:type data)))
            (is (contains? data :line))
            (is (contains? data :column))
            (is (contains? data :file)))

          ;; (pst e)
          ))
      ))

  (deftest test-require-order
    (let [{:keys [deps] :as ns-ast}
          (ns-form/parse
            '(ns something
               (:use [test.c :only [that]])
               (:import [goog.net XhrIo])
               (:require [test.b :as b]
                         [test.a :as a]
                         [test.d]
                         test.e)))]

      (pprint ns-ast)
      (is (= deps '[cljs.core test.c goog.net.XhrIo test.b test.a test.d test.e]))
      ))


  (deftest test-excute-affected-tests
    (-> (api/init-state)
        (assoc :optimizations :none
          :pretty-print true
          :work-dir (io/file "target/test-cljs-work")
          :cache-dir (io/file "target/test-cljs-cache")
          :cache-level :jars
          :output-dir (io/file "target/test-cljs")
          :asset-path "target/test-cljs")
        (api/find-resources-in-classpath)
        (api/find-resources "cljs-data/dummy/src")
        (api/find-resources "cljs-data/dummy/test")
        (node/execute-affected-tests! ["basic.cljs"])))


  (deftest test-nodejs-build
    (let [state
          (-> (api/init-state)
              ;; (api/enable-emit-constants)
              (api/find-resources-in-classpath)
              (api/find-resources "cljs-data/node/src")

              (merge {:cache-dir (io/file "target/test-cljs-cache")
                      :cache-level :jars})

              (node/configure
                {:main 'test.server/main
                 :output-to "cljs-data/node/out/my-app.js"})
              (node/compile)
              (node/flush-unoptimized))]

      (println (slurp "cljs-data/node/out/my-app.js"))
      ;; (println (slurp "cljs-data/node/out/src/test/server.js"))

      ))

  (def ns-tests
    "taken from https://github.com/clojure/clojurescript/blob/master/test/clj/api/analyzer_tests.clj"
    ['(ns foo.bar
        (:require {:foo :bar}))
     "Only [lib.ns & options] and lib.ns specs supported in :require / :require-macros"
     '(ns foo.bar
        (:require [:foo :bar]))
     "Library name must be specified as a symbol in :require / :require-macros"
     '(ns foo.bar
        (:require [baz.woz :as woz :refer [] :plop]))
     "Only :as alias and :refer (names) options supported in :require"
     '(ns foo.bar
        (:require [baz.woz :as woz :refer [] :plop true]))
     "Only :as and :refer options supported in :require / :require-macros"
     '(ns foo.bar
        (:require [baz.woz :as woz :refer [] :as boz :refer []]))
     "Each of :as and :refer options may only be specified once in :require / :require-macros"
     '(ns foo.bar
        (:refer-clojure :refer []))
     "Only [:refer-clojure :exclude (names)] form supported"
     '(ns foo.bar
        (:use [baz.woz :exclude []]))
     "Only [lib.ns :only (names)] specs supported in :use / :use-macros"
     '(ns foo.bar
        (:require [baz.woz :as []]))
     ":as must be followed by a symbol in :require / :require-macros"
     '(ns foo.bar
        (:require [baz.woz :as woz]
                  [noz.goz :as woz]))
     ":as alias must be unique"
     '(ns foo.bar
        (:unless []))
     "Only :refer-clojure, :require, :require-macros, :use and :use-macros libspecs supported"
     '(ns foo.bar
        (:require baz.woz)
        (:require noz.goz))
     "Only one "
     ])

  (deftest test-caching-rountrip
    (let [out (File. "target/dummy.cache")
          data {:dummy "data"
                :url (URL. "http://github.com")}
          read (do (cache/write-cache out data)
                   (cache/read-cache out))]
      (is (= data read))))


  (comment
    ;; node.js hurts my head .. how is this node_modules/thing/node_modules/other/node_modules/thing/node_modules shit ok?
    (defn get-module-roots []
      (->> (file-seq (io/file "cljs-data/commonjs/node_modules"))
           (filter #(.isDirectory %))
           (filter #(re-find #"node_modules/[^/]+$" (.getAbsolutePath %)))
           (map #(.getAbsolutePath %))
           (distinct)
           (into [])
           ))

    (deftest test-es6-conversion
      (let [cc (api/make-closure-compiler)
            co (doto (CompilerOptions.)
                 (.setPrettyPrint true)
                 (.setModuleRoots (get-module-roots))
                 (.setCodingConvention (ClosureCodingConvention.))
                 (.setParseJsDocDocumentation false)
                 (.setProcessCommonJSModules true))

            file (io/file "cljs-data/commonjs/node_modules/react")
            files (->> (file-seq file)
                       (filter #(.isFile %))
                       (filter #(.endsWith (.getName %) ".js"))
                       (remove #(.contains (.getAbsolutePath %) "/test"))
                       (remove #(.contains (.getAbsolutePath %) "__tests__"))
                       (map #(SourceFile/fromFile %))
                       (into []))]
        (.compile cc [] files co)


        ;; (pprint (.getInputsById cc))

        (spit "tmp/wahnsinn.js" (.toSource cc))
        )))

  (deftest test-ignore-patterns
    (let [state (api/init-state)]
      (is (api/should-ignore-resource? state "node_modules/react/addons.js"))
      (is (not (api/should-ignore-resource? state "shadow/dom.js")))
      ))


  (defn basic-repl-setup []
    (-> (api/init-state)
        (api/enable-source-maps)
        (assoc
          :optimizations :none
          :pretty-print true
          :cache-level :jars
          :output-dir (io/file "target/repl-test"))
        (api/find-resources-in-classpath)

        (api/finalize-config)
        (api/configure-module :cljs ['cljs.core] #{})
        (repl/prepare)))


  (deftest test-plain-js
    (let [{:keys [repl-state] :as s}
          (-> (basic-repl-setup)
              ;; (repl/process-input "(js/console.log \"yo\")")
              (repl/process-input "(def x 1)")
              (repl/process-input "x")
              (repl/process-input "js/test"))
          action (get-in s [:repl-state :repl-actions 0])]
      (pprint action)))

  (deftest test-repl-dump
    (let [{:keys [repl-state] :as s}
          (-> (basic-repl-setup)
              (repl/process-input "(repl-dump)"))]
      (pprint repl-state)))

  (deftest test-basic-require
    (let [{:keys [repl-state] :as s}
          (-> (basic-repl-setup)
              (repl/process-input "(require 'demo.npm)"))
          action (get-in s [:repl-state :repl-actions 0])]
      ;; (pprint repl-state)
      (pprint action)
      ))

  (deftest test-repl-ns
    (let [{:keys [repl-state] :as s}
          (-> (basic-repl-setup)
              (repl/process-input "(ns demo.foo (:require [demo.npm :as npm]))"))
          action (get-in s [:repl-state :repl-actions 0])]
      (pprint repl-state)
      ;; (pprint action)
      ))

  (deftest test-repl-ns-additive
    (let [{:keys [repl-state] :as s}
          (-> (basic-repl-setup)
              (repl/process-input "(ns demo.foo (:require [demo.npm :as npm]))")
              (repl/process-input "(def x 1)")
              (repl/process-input "(npm/foo)")
              (repl/process-input "(ns demo.foo)")
              (repl/process-input "(npm/foo)")
              (repl/process-input "x")
              )]
      (pprint repl-state)
      ;; (pprint action)
      ))

  (deftest test-repl-string-ns
    (let [{:keys [repl-state] :as s}
          (-> (basic-repl-setup)
              (repl/process-input "(ns demo.foo (:require [\"fs\" :as fs]))"))
          action (get-in s [:repl-state :repl-actions 0])]
      (pprint repl-state)
      (is (not (nil? (get-in s [:sources "shadow.npm.fs.js"]))))
      (is (not (nil? (get-in s [:provide->source 'shadow.npm.fs]))))
      ;; (pprint action)
      ))


  (deftest test-basic-def
    (let [{:keys [repl-state] :as s}
          (-> (basic-repl-setup)
              (repl/process-input "(def x 1)")
              (repl/process-input "(inc x)")
              (repl/process-input "x"))]

      (pprint repl-state)
      ))

  (deftest test-repl-if
    (let [{:keys [repl-state] :as s}
          (-> (basic-repl-setup)
              (repl/process-input "(if (= 1 2) 1 2)"))]

      (pprint repl-state)
      ))

  (deftest test-basic-require-and-call
    (let [{:keys [repl-state] :as s}
          (-> (basic-repl-setup)
              (repl/process-input "(require '[clojure.string :as str])")
              (repl/process-input "(str/trim \"hello world \")"))]

      (pprint repl-state)
      ))

  (deftest test-require-with-reload
    (let [s (-> (basic-repl-setup)
                (repl/process-input "(require ['basic :as 'something] :reload-all)"))
          action (get-in s [:repl-state :repl-actions 0])]
      (pprint (:repl-state s))
      (pprint action)))

  (deftest test-basic-require-with-macro
    (let [{:keys [repl-state] :as s}
          (-> (basic-repl-setup)
              (repl/process-input "(require '[shadow.test-macro :as tm])")
              ;; this is a macro that should emit (prn ...) not call shadow.test_macro.hello()
              (repl/process-input "(tm/hello)"))]

      (pprint (:repl-actions repl-state))
      ))

  (deftest test-in-ns
    (let [{:keys [repl-state] :as state}
          (-> (basic-repl-setup)
              (repl/process-input "(in-ns 'basic)"))

          {:keys [repl-actions]} repl-state]

      (is (= 1 (count repl-actions)))
      (is (= :repl/set-ns (-> repl-actions first :type)))
      (is (= 'basic (-> repl-actions first :ns)))
      ))


  (deftest test-load-file
    (let [abs-path (.getAbsolutePath (io/file "cljs-data" "dummy" "src" "basic.cljs"))
          {:keys [repl-state] :as state}
          (-> (basic-repl-setup)
              (repl/process-input (str "(load-file \"" abs-path "\")")))

          {:keys [repl-actions]} repl-state]

      (prn repl-state)
      (pprint repl-actions)
      ))

  (deftest test-alias-constants
    (let [state
          (-> (api/init-state)
              (api/merge-compiler-options
                {:optimizations :advanced
                 :pretty-print true})
              (api/merge-build-options
                {:output-dir (io/file "target/test-alias-constants")
                 :asset-path "out"})
              (api/find-resources-in-classpath)
              (api/configure-module :cljs '[cljs.core] #{})
              (api/configure-module :a ['code-split.a] #{:cljs})
              (api/configure-module :b ['code-split.b] #{:a})
              (api/configure-module :c ['code-split.c] #{:cljs})
              (api/compile-modules)
              (api/closure-optimize)
              (api/flush-modules-to-disk)
              ;; (api/flush-unoptimized)
              )]

      (println (-> state ::closure/modules (nth 2) :output))
      (prn [:done])
      ))


  (deftest test-elide-asserts
    (let [state
          (-> (api/init-state)
              (api/find-resources-in-classpath)
              (api/find-resources "cljs-data/dummy/src")
              (api/prepare-compile)
              (api/merge-build-options
                {:asset-path "target/assert-out"
                 :output-dir (io/file "target/assert-out")})
              (api/merge-compiler-options
                {:pretty-print true
                 :pseudo-names true
                 :closure-defines {"cljs.core._STAR_assert_STAR_" false}})
              (api/configure-module :cljs '[cljs.core] #{})
              (api/configure-module :test '[with-asserts] #{:cljs})
              (api/compile-modules)
              (api/closure-optimize :advanced)
              (api/flush-modules-to-disk))]
      (println (-> state ::closure/modules second :output))

      ))


  (deftest trying-to-break-static-fns
    (let [{:keys [repl-state] :as state}
          (-> (basic-repl-setup)
              (repl/process-input "(def x (fn [a b] (+ a b)))")
              (repl/process-input "(def y (fn [] (x 1 2)))")
              (repl/process-input "(def x (reify IFn (-invoke [_ a b] (- a b))))")
              (repl/process-input "(binding [x (fn [& args] (apply - args))] (y))")
              (repl/process-input "(y)")
              )]
      (->> repl-state
           :repl-actions
           (map :js)
           (map println)
           (doall)
           )))

  (deftest run-all-tests-breakage
    (let [{:keys [repl-state] :as state}
          (-> (basic-repl-setup)
              (repl/process-input "(require 'cljs.test)")
              (repl/process-input "(cljs.test/run-all-tests)"))]

      (pprint repl-state)
      ))


  (deftest test-repl-load-file
    (let [abs-file
          (-> (io/file "cljs-data/dummy/src/basic.cljs")
              (.getAbsolutePath))

          {:keys [repl-state] :as state}
          (-> (basic-repl-setup)
              (repl/process-input (str "(load-file \"" abs-file "\")")))]

      (pprint repl-state)
      ))

  (deftest test-repl-require
    (let [{:keys [repl-state] :as state}
          (basic-repl-setup)]

      (pprint (:current repl-state))

      (let [{:keys [repl-state] :as state}
            (-> state
                (repl/process-input "(require 'clojure.string)"))]

        (pprint (:current repl-state)))
      ))

  (deftest test-repl-source-map
    (let [{:keys [repl-state] :as state}
          (-> (basic-repl-setup)
              (repl/prepare)
              (repl/process-input "(let [x 1] (throw (js/Error. \"foo\")))"))]

      ;; (pprint repl-state)
      ))

  (deftest test-auto-alias-clojure-to-cljs
    (let [state
          (-> (api/init-state)
              (assoc :cache-level :jars)
              (api/find-resources-in-classpath)
              (api/find-resources "cljs-data/auto-alias")
              (api/prepare-compile)
              (api/merge-build-options
                {:asset-path "target/auto-alias-out"
                 :output-dir (io/file "target/auto-alias-out")})
              (api/configure-module :test '[test.alias] #{})
              (api/compile-modules))

          output
          (get-in state [:sources "test/alias.cljs" :output])]

      (is (not (str/includes? output "clojure.pprint")))
      (println output)
      ))

  (deftest test-repl-stream
    (let [in
          (ByteArrayInputStream. (.getBytes "(def foo 1)"))

          {:keys [compiler-env repl-state] :as state}
          (-> (api/init-state)
              (api/find-resources-in-classpath)
              (repl/prepare)
              (repl/process-input-stream in)
              )]

      (pprint (:repl-actions repl-state))))

  (deftest test-closure-source-maps
    (let [state
          (-> (api/init-state)
              (api/enable-source-maps)
              (assoc :cache-level :jars)

              (api/merge-build-options
                {:output-dir (io/file "cljs-data" "closure-source-maps" "out")
                 :asset-path "out"})
              (api/merge-compiler-options
                {:optimizations :advanced})
              (api/find-resources-in-classpath)
              (api/find-resources "cljs-data/closure-source-maps/src")
              (api/configure-module :a '[test.a] #{})
              (api/configure-module :b '[test.foo test.b] #{:a})
              (api/compile-modules)
              (api/closure-optimize)
              (api/flush-modules-to-disk))]
      :done
      ))

  (deftest test-es6-import
    (let [{:keys [sources] :as state}
          (-> (api/init-state)
              (assoc :cache-level :jars)
              (api/merge-build-options
                {:output-dir (io/file "cljs-data" "es6" "out")
                 :asset-path "out"})
              (api/merge-compiler-options
                {:pretty-print false})
              (api/enable-source-maps)
              (api/find-resources-in-classpath)
              (api/find-resources "cljs-data/es6/lib-a")
              (api/find-resources "cljs-data/es6/lib-b")
              (api/configure-module :a '[cljs.core] #{})
              (api/configure-module :b '[test.b] #{:a})
              (api/configure-module :c '[test.c] #{:b})
              (api/compile-modules)
              (api/flush-unoptimized))]

      :done
      ))


  (deftest test-module-file-moving
    ;; FIXME: this relies on array-map
    ;; otherwise a,b,c,d,e,f
    ;; could be ordered as a,c,d,f,e,b or others
    (let [modules
          {:a {:name :a :sources ["a"]}
           :b {:name :b :sources ["b" "X"] :depends-on #{:a}}
           :c {:name :c :sources ["c" "X"] :depends-on #{:a}}
           :d {:name :d :sources ["d"] :depends-on #{:c}}
           :e {:name :e :sources ["e" "Y"] :depends-on #{:d}}
           :f {:name :f :sources ["f" "Y"] :depends-on #{:d}}
           }

          sorted
          (api/compact-build-modules {:modules modules})

          expected
          ;; X moves here
          [{:name :a, :sources ["a" "X"]}
           {:name :b, :sources ["b"], :depends-on #{:a}}
           {:name :c, :sources ["c"], :depends-on #{:a}}
           ;; Y moves here but not to :a
           {:name :d, :sources ["d" "Y"], :depends-on #{:c}}
           {:name :e, :sources ["e"], :depends-on #{:d}}
           {:name :f, :sources ["f"], :depends-on #{:d}}]]

      (is (= expected sorted))
      ))

  (deftest test-module-ordering
    (let [{:keys [modules] :as state}
          (-> (api/init-state)
              (api/configure-module :c [] #{:b})
              (api/configure-module :d [] #{:a})
              (api/configure-module :a [] #{})
              (api/configure-module :b [] #{:a})
              (api/configure-module :e [] #{:b :d})
              )

          sorted
          (api/topo-sort-modules modules)]

      ;; FIXME: also valid
      ;; [:a :d :b :c :e]
      ;; [:a :b :d :e :c]
      (is (= sorted [:a :b :c :d :e]))
      ))


  (deftest test-random-jar
    (let [{:keys [resources externs] :as x}
          (-> (api/init-state)
              (api/do-find-resources-in-path
                "/Users/zilence/.m2/repository/cljsjs/google-maps/3.18-1/google-maps-3.18-1.jar"
                #_"/Users/zilence/.m2/repository/datascript/datascript/0.15.4/datascript-0.15.4.jar"))]
      (doseq [x resources]
        (pprint (dissoc x :input :output :externs-source)))

      (pprint externs)
      ))

  (deftest test-cross-module-reference
    (try
      (let [state
            (-> (comp/configure :release
                  '{:id :cross-module-reference
                    :target :browser

                    ;;:target :browser
                    :output-dir "target/cross-module-reference/js"
                    :asset-path "/js"

                    :module-loader true
                    :modules
                    {:loader
                     {:entries
                      [shadow.loader]}
                     :core
                     {:entries
                      [cljs.core]
                      :depends-on #{:loader}}
                     :a
                     {:entries
                      [code-split.a]
                      :depends-on #{:core}}
                     :b
                     {:entries
                      [code-split.b]
                      :depends-on #{:a}}
                     :c
                     {:entries
                      [code-split.c]
                      :depends-on #{:a}}
                     :d
                     {:entries
                      [test.bar]
                      :depends-on #{:core}}}
                    })
                ;; (api/enable-source-maps)
                (comp/compile)
                (comp/optimize)
                (comp/flush))]

        :done)
      (catch Exception e
        (prn e)
        (let [{:keys [errors]} (ex-data e)]
          (doseq [err errors]
            (prn err))))))

  (deftest test-closure-module-per-file
    (try
      (let [state
            (-> (comp/configure :release
                  '{:id :test
                    :target :npm-module
                    :entries [code-split.b]

                    ;;:target :browser
                    :output-dir "target/closure-module-per-file"

                    #_:modules
                    #_{:core
                       {:entries
                        [cljs.core]}
                       :a
                       {:entries
                        [code-split.a]
                        :depends-on #{:core}}
                       :b
                       {:entries
                        [code-split.b]
                        :depends-on #{:a}}
                       }
                    })
                ;; (api/enable-source-maps)
                (comp/compile)
                (comp/optimize)
                (comp/flush))]

        :done)
      (catch Exception e
        (prn e)
        (let [{:keys [errors]} (ex-data e)]
          (doseq [err errors]
            (prn err))))))


  (deftest test-js-require
    (let [{:keys [compiler-env] :as state}
          (-> (comp/configure :release
                '{:id :test
                  :target :npm-module
                  :entries [demo.browser]})
              (comp/compile)
              )]

      (api/print-warnings! state)

      (pprint (:js-module-index compiler-env))
      ))


  (deftest test-build-errors
    (try
      (-> (api/init-state)
          (api/find-resources-in-classpath)
          (api/configure-module :main '[demo.errors] #{})
          (api/compile-modules))
      (catch ExceptionInfo ex
        (let [data (ex-data ex)]
          (is (contains? data :source-excerpt))
          )))
    ))