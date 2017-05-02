(ns shadow.cljs.devtools_test
  (:require [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [clojure.pprint :refer (pprint)]
            [clojure.test :refer :all]
            [shadow.cljs.devtools.targets.browser :as browser]
            [shadow.cljs.devtools.compiler :as comp]
            [cljs.externs :as externs]
            [cljs.analyzer :as ana]
            [cljs.compiler :as cljs-comp]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.embedded :as em]
            [clojure.repl :as repl])
  (:import (com.google.javascript.jscomp SourceFile CompilationLevel DiagnosticGroups CheckLevel DiagnosticGroup VarCheck)))


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
               :externs ["tmp/externs.js"]
               ;; :externs-sources [(SourceFile/fromFile (io/file "tmp/test.externs.js"))]
               })
            (cljs/merge-compiler-options
              {:optimizations :advanced
               :pretty-print true
               :pseudo-names false
               :closure-warnings
               {:check-types :warning
                :check-variables :warning
                :undefined-variables :warning}})
            (cljs/add-closure-configurator
              (fn [cc co state]
                (.setTypeBasedOptimizationOptions CompilationLevel/ADVANCED_OPTIMIZATIONS co)
                ;; (.setPrintSourceAfterEachPass co true)
                ))

            ;; (cljs/enable-source-maps)
            (cljs/find-resources-in-classpath)

            (cljs/configure-module :test '[cljs.core] #{})
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

(deftest test-foreign
  (api/release :foreign {:source-maps true}))

(defn load-from-disk [{:keys [public-dir build-sources] :as state}]
  (-> state
      (cljs/prepare-compile)
      (cljs/prepare-modules)
      (as-> state
        (reduce
          (fn [state source-name]
            (prn [:load source-name])
            (let [{:keys [js-name] :as rc}
                  (get-in state [:sources source-name])

                  target-file
                  (io/file public-dir "cljs-runtime" js-name)]

              (when-not (.exists target-file)
                (throw (ex-info (format "cannot load file %s" target-file) {:src source-name :file target-file})))

              (let [content (slurp target-file)]
                (when-not (seq content)
                  (throw (ex-info (format "no content %s" target-file) {})))
                (assoc-in state [:sources source-name :output] content))
              ))
          state
          build-sources))))



#_(doseq [prop protocol-props]
    (.registerPropertyOnType type-reg prop obj-type))
;; (.resetWarningsGuard co)
;; (.setWarningLevel co DiagnosticGroups/CHECK_TYPES CheckLevel/OFF)
;; really only want the undefined variables warnings
;; (.setWarningLevel co DiagnosticGroups/CHECK_VARIABLES CheckLevel/OFF)
;; (.setWarningLevel co DiagnosticGroups/UNDEFINED_VARIABLES CheckLevel/WARNING)
;; (.setWarningLevel co DiagnosticGroups/MISSING_SOURCES_WARNINGS CheckLevel/WARNING)
;; (.setWarningLevel co (DiagnosticGroup/forType VarCheck/UNDEFINED_VAR_ERROR) CheckLevel/WARNING)
;; (.setTypeBasedOptimizationOptions CompilationLevel/ADVANCED_OPTIMIZATIONS co)
;; (.setPrintSourceAfterEachPass co true)

(deftest test-warnings
  (try
    (let [{:keys [compiler-env closure-compiler] :as state}
          (-> (cljs/init-state)
              (cljs/merge-build-options
                {:public-dir (io/file "out" "demo-foreign" "js")
                 :public-path "js"
                 :cache-level :off

                 ;; :externs-sources [(SourceFile/fromFile (io/file "tmp/test.externs.js"))]
                 })
              (cljs/merge-compiler-options
                {:optimizations :advanced
                 :pretty-print false
                 :pseudo-names false
                 :externs
                 ["shadow/cljs/externs.js"]
                 :closure-warnings
                 {:check-types :warning}
                 })
              (cljs/enable-source-maps)
              (cljs/find-resources-in-classpath)

              (cljs/configure-module :main
                '[demo.prototype demo.foreign]
                #{}
                {:prepend-js
                 "goog.nodeGlobalRequire = function(/** String */ name) {};"})
              ;; (load-from-disk)
              (cljs/compile-modules)
              ;; (cljs/flush-unoptimized) ;; doesn't work
              ;; (cljs/flush-unoptimized-compact)
              (cljs/closure-check)
              ;; (cljs/flush-modules-to-disk)
              )]

      :done)
    (catch Exception e
      (repl/pst e))))

(comment
  (em/start! {:verbose true})
  (em/start-worker :browser)
  (em/start-worker :script)
  (em/stop!)

  (def file (io/file "src/dev/demo/browser.cljs"))

  (def content (slurp file))

  ;; simulate empty file
  (spit file "")
  (spit file content)
  )