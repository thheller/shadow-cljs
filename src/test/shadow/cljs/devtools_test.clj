(ns shadow.cljs.devtools_test
  (:require [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.pprint :refer (pprint)]
            [clojure.test :refer :all]
            [shadow.cljs.devtools.targets.browser :as browser]
            [shadow.cljs.devtools.compiler :as comp]
            [cljs.externs :as externs]
            [shadow.cljs.devtools.api :as api])
  (:import (com.google.javascript.jscomp SourceFile CompilationLevel)))


(comment
  ;; this should be useful for some devtools removal in production
  ;; see StripCode in closure
  (cljs/add-closure-configurator
    (fn [cc co state]
      (set! (.-stripTypePrefixes co) #{"goog.log"})
      (set! (.-stripNameSuffixes co) #{"logger" "logger_"})
      )))



(defn test-loader []
  (let [config
        '{:id :loader
          :target :browser
          :public-dir "target/module-loader"
          :public-path "/module-loader"
          :module-loader true
          :modules
          {:core
           {:entries [cljs.core]}
           :foo
           {:entries [test.foo]
            :depends-on #{:core}}
           :bar
           {:entries [test.bar]
            :depends-on #{:foo}}
           }}

        state
        (-> (comp/init :release config)
            (comp/compile)
            (comp/flush))]

    :done
    ))

(deftest test-module-loader
  (test-loader))

(deftest test-code-snippet
  (let [{:keys [compiler-env] :as state}
        (-> (cljs/init-state)
            (cljs/merge-build-options
              {:public-dir (io/file "target" "test-snippet")
               :public-path "/"
               :infer-externs true
               :externs-sources [(SourceFile/fromFile (io/file "tmp/test.externs.js"))]})
            (cljs/merge-compiler-options
              {:optimizations :none
               :pretty-print false
               :pseudo-names false})

            (cljs/enable-source-maps)
            (cljs/find-resources-in-classpath)

            (cljs/configure-module :test '[test.snippet] #{})
            (cljs/compile-modules)
            ;; (cljs/flush-unoptimized) ;; doesn't work
            ;; (cljs/flush-unoptimized-compact)
            ;; (cljs/closure-optimize)
            ;; (cljs/flush-modules-to-disk)
            )]

    (binding [*print-meta* true]

      (println (get-in state [:sources "test/snippet.cljs" :output]))
      (pprint (get-in compiler-env [:cljs.analyzer/externs 'Foreign]))
      (pprint (get-in compiler-env [:cljs.analyzer/namespaces 'test.snippet :externs])))
    )
  :done)

(deftest test-ext
  (let [{:keys [compiler-env closure-compiler] :as state}
        (-> (cljs/init-state)
            (cljs/merge-build-options
              {:public-dir (io/file "target" "test-ext")
               :public-path "/"
               :infer-externs true
               ;; :externs ["tmp/test.externs.js"]
               ;; :externs-sources [(SourceFile/fromFile (io/file "tmp/test.externs.js"))]
               })
            (cljs/merge-compiler-options
              {:optimizations :advanced
               :pretty-print true
               :pseudo-names false
               :closure-warnings
               {:check-types :warning}})
            (cljs/add-closure-configurator
              (fn [cc co state]
                (.setTypeBasedOptimizationOptions CompilationLevel/ADVANCED_OPTIMIZATIONS co)
                (prn co)
                ))

            (cljs/enable-source-maps)
            (cljs/find-resources-in-classpath)

            (cljs/configure-module :test '[test.ext] #{})
            (cljs/compile-modules)
            ;; (cljs/flush-unoptimized) ;; doesn't work
            ;; (cljs/flush-unoptimized-compact)
            (cljs/closure-optimize)
            (cljs/flush-modules-to-disk))]

    ;; (prn (.getExternProperties closure-compiler))

    (println (get-in state [:optimized 0 :output]))

    #_(binding [*print-meta* true]

        (println (get-in state [:sources "test/snippet.cljs" :output]))
        (pprint (get-in compiler-env [:cljs.analyzer/externs 'Foreign]))
        (pprint (get-in compiler-env [:cljs.analyzer/namespaces 'test.snippet :externs])))
    )
  :done)


(deftest test-externs-map
  (let [x (externs/externs-map)]
    (binding [*print-meta* true]

      (-> x
          (get 'Window)
          (get 'prototype)
          (keys)
          (sort)
          (pprint))
      )))

(deftest test-parse-externs
  (let [x (externs/parse-externs (SourceFile/fromFile (io/file "tmp" "test.externs.js")))]
    (binding [*print-meta* true]
      (pprint x))))

(deftest test-error-msg
  (api/release :custom {:debug true}))

(deftest test-warnings
  (api/once :warnings))

