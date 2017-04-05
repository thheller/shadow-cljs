(ns shadow.cljs.devtools_test
  (:require [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.pprint :refer (pprint)]
            [clojure.test :refer :all]
            [shadow.cljs.devtools.targets.browser :as browser]))


(comment
  ;; this should be useful for some devtools removal in production
  ;; see StripCode in closure
  (cljs/add-closure-configurator
    (fn [cc co state]
      (set! (.-stripTypePrefixes co) #{"goog.log"})
      (set! (.-stripNameSuffixes co) #{"logger" "logger_"})
      )))

(defn test-loader []
  (let [state
        (-> (cljs/init-state)
            (cljs/merge-build-options
              {:public-dir (io/file "target" "module-loader")
               :public-path "/module-loader"})
            (cljs/merge-compiler-options
              {:optimizations :advanced
               :pretty-print false
               :pseudo-names false})

            (cljs/enable-source-maps)
            (cljs/find-resources-in-classpath)

            (cljs/configure-module :core '[cljs.core] #{})
            (cljs/configure-module :foo ['test.foo] #{:core})
            (cljs/configure-module :bar ['test.bar] #{:foo})
            (browser/create-loader)

            (cljs/compile-modules)
            ;; (cljs/flush-unoptimized) ;; doesn't work
            (cljs/flush-unoptimized-compact)
            ;; (cljs/closure-optimize)
            ;; (cljs/flush-modules-to-disk)
            )]

    :done
    ))

(deftest test-module-loader
  (test-loader))
