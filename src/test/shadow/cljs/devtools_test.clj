(ns shadow.cljs.devtools_test
  (:require [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.pprint :refer (pprint)]
            [clojure.test :refer :all]
            [shadow.cljs.devtools.targets.browser :as browser]
            [shadow.cljs.devtools.server.compiler :as comp]))


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
  (-> (cljs/init-state)
      (cljs/merge-build-options
        {:public-dir (io/file "target" "test-snippet")
         :public-path "/"})
      (cljs/merge-compiler-options
        {:optimizations :none
         :pretty-print false
         :pseudo-names false})

      (cljs/enable-source-maps)
      (cljs/find-resources-in-classpath)

      (cljs/configure-module :test '[test.snippet] #{})
      (cljs/compile-modules)
      (cljs/flush-unoptimized) ;; doesn't work
      ;; (cljs/flush-unoptimized-compact)
      ;; (cljs/closure-optimize)
      ;; (cljs/flush-modules-to-disk)
      )
  :done)