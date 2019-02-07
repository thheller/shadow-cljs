(ns shadow.cljs.devtools_test
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :refer (sh)]
    [clojure.data.json :as json]
    [clojure.pprint :refer (pprint)]
    [clojure.test :refer :all]
    [clojure.walk :as walk]
    [clojure.repl :as repl]
    [cljs.externs :as externs]
    [cljs.analyzer :as ana]
    [cljs.compiler :as cljs-comp]
    [cljs.env :as cljs-env]
    [cljs.analyzer.api :as ana-api]
    [shadow.build.api :as build-api]
    [shadow.build.targets.browser :as browser]
    [shadow.build :as comp]
    [shadow.build.npm :as npm]
    [shadow.build.closure :as closure]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.devtools.server.util :as util]
    [shadow.cljs.devtools.errors :as errors]
    [shadow.cljs.devtools.server.worker.impl :as worker]
    [shadow.build.data :as data]
    [clojure.string :as str]
    [shadow.build.cache :as cache])
  (:import (com.google.javascript.jscomp SourceFile CompilationLevel DiagnosticGroups CheckLevel DiagnosticGroup VarCheck)
           (javax.net.ssl KeyManagerFactory)
           (java.io FileInputStream)
           (java.security KeyStore)))


(comment
  ;; this should be useful for some devtools removal in production
  ;; see StripCode in closure
  (build-api/add-closure-configurator
    (fn [cc co state]
      (set! (.-stripTypePrefixes co) #{"console.log"})
      ;; (set! (.-stripNameSuffixes co) #{"logger" "logger_"})
      )))

(defn test-loader []
  (let [config
        '{:id :loader
          :target :browser
          :public-dir "target/module-loader"
          :asset-path "/module-loader"
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
        (-> (comp/configure :release config {})
            (comp/compile)
            (comp/flush))]

    :done
    ))

(deftest test-module-loader
  (test-loader))

(deftest test-code-snippet
  (let [{:keys [compiler-env] :as state}
        (-> (build-api/init)
            (build-api/merge-build-options
              {:public-dir (io/file "target" "test-snippet")
               :asset-path "/"
               ;; :infer-externs true
               ;; :externs-sources [(SourceFile/fromFile (io/file "src/test/test.externs.js"))]
               })
            (build-api/merge-compiler-options
              {:optimizations :advanced
               :pretty-print false
               :pseudo-names false
               :externs
               ["test.externs.js"]})

            (build-api/enable-source-maps)
            (build-api/configure-modules
              '{:base {:entries [cljs.core]
                       :depends-on #{}}
                :test {:entries [test.snippet]
                       :depends-on #{:base}}})
            (build-api/analyze-modules)
            (build-api/compile-sources)
            ;; (api/flush-unoptimized) ;; doesn't work
            (build-api/optimize)
            ;; (api/flush-modules-to-disk)
            )]

    (binding [*print-meta* true]
      (println (get-in state [:sources "test/snippet.cljs" :output]))
      (println (get-in state [::closure/modules 1 :output]))
      ))
  :done)

(deftest test-ext
  (let [{:keys [compiler-env closure-compiler] :as state}
        (-> (build-api/init)
            (build-api/merge-build-options
              {:public-dir (io/file "target" "test-ext")
               :asset-path "/"
               :infer-externs true
               ;; :externs ["test.externs.js"]
               ;; :externs-sources [(SourceFile/fromFile (io/file "tmp/test.externs.js"))]
               })
            (build-api/merge-compiler-options
              {:optimizations :advanced
               :pretty-print true
               :pseudo-names false

               :closure-warnings
               {:check-types :warning
                ;; :report-unknown-types :warning ;; doesn't work? reports huge amounts of errors in goog/*
                :type-invalidation :warning
                ;; :check-variables :warning
                ;; :undefined-variables :warning
                }})
            (build-api/add-closure-configurator
              (fn [cc co state]
                (.setCheckTypes co true)
                (.setTypeBasedOptimizationOptions CompilationLevel/ADVANCED_OPTIMIZATIONS co)
                ;; (.setPrintSourceAfterEachPass co true)
                ))

            ;; (api/enable-source-maps)

            (build-api/configure-modules
              '{:test {:entries [test.snippet]
                       :depends-on #{}}})

            (build-api/analyze-modules)
            (build-api/compile-sources)
            ;; (api/flush-unoptimized) ;; doesn't work
            ;; (api/closure-optimize)
            ;; (api/flush-modules-to-disk)
            )]

    ;; (prn (.getExternProperties closure-compiler))

    ;; (println (get-in state [:optimized 1 :output]))

    (binding [*print-meta* true]
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

(deftest test-build-warnings
  (api/compile :warnings))

(deftest test-build-errors
  (api/compile :errors))

(deftest test-browser
  (api/compile :browser))

(deftest test-browser-check
  (api/check :browser))

(deftest test-browser-release
  (api/release :browser {} #_{:source-maps true}))

(deftest test-browser-release-pseudo
  (api/release :browser {:pseudo-names true}))

(deftest test-script
  (api/compile :self-contained-script))

(deftest test-library
  (api/compile :library))

(deftest test-npm
  (api/compile :npm))

(deftest test-npm-release
  (api/release :npm))

(deftest test-build-bootstrap-host
  (api/compile :bootstrap-host))

(deftest test-build-bootstrap-support
  (api/compile :bootstrap-support))

(deftest test-js-only
  (api/compile :js-only))

(deftest test-sm-release
  (api/release :sm-test))

(deftest test-closure
  (api/compile :closure))

(deftest test-closure-es6
  (api/compile :closure-es6))

(deftest test-closure-release
  (api/release :closure))

(deftest test-extract-reload-info
  (let [build-state
        (api/compile* (api/get-build-config :browser) {})]
    (prn (worker/extract-reload-info build-state))
    ))

(comment
  (deftest test-ssl-context
    (let [cert-file (io/file "ssl" "cert.pem")
          key-file (io/file "ssl" "key.pem")
          context
          (-> (SslContextBuilder/forServer cert-file key-file)
              (.build))]

      (prn [:context context])
      ))

  (deftest test-ssl-setup
    (let [key-manager
          (KeyManagerFactory/getInstance
            (KeyManagerFactory/getDefaultAlgorithm))

          key-store
          (KeyStore/getInstance
            (KeyStore/getDefaultType))

          pw
          (.toCharArray "foobar")]

      (with-open [fs (FileInputStream. "ssl/keystore.jks")]
        (.load key-store fs pw))

      (.init key-manager key-store pw)

      (-> (SslContextBuilder/forServer key-manager)
          (.build))
      (prn [:x key-store])
      )))

(deftest test-infer-externs
  (try
    (let [{:keys [compiler-env] :as state}
          (api/compile*
            '{:build-id :infer-externs
              :target :browser

              :output-dir "target/test-infer-externs/js"
              :asset-path "/js"

              :compiler-options
              {:infer-externs :auto}

              :build-options
              {:cache-blockers #{demo.stuff}}

              :modules
              {:base
               {:entries [demo.stuff]}}

              :js-options
              {}}
            {})]


      #_(pprint (->> (get-in compiler-env [:shadow/js-properties])))

      (-> (get-in state [:output [:shadow.build.classpath/resource "demo/stuff.cljs"]])
          (:js)
          (println)
          )

      (doseq [ns-info (->> '[demo.browser]
                           (map #(get-in compiler-env [:cljs.analyzer/namespaces %])))]
        (prn (:name ns-info))
        (pprint (-> ns-info
                    (select-keys [:shadow/js-access-global
                                  :shadow/js-access-properties]))))

      )
    (catch Exception ex
      (errors/user-friendly-error ex))))


(deftest test-circular-dependency
  (try
    (api/compile*
      '{:build-id :circle-detection
        :target :browser

        :output-dir "target/test-circular/js"
        :asset-path "/js"

        :modules
        {:base
         {:entries [circular.main]}}}
      {})

    (catch Exception ex
      (errors/user-friendly-error ex))))


(deftest test-build-with-reagent
  (try
    (api/compile*
      '{:build-id :reagent
        :target :browser

        :output-dir "target/test-build-with-reagent/js"
        :asset-path "/js"

        :modules
        {:base
         {:entries [cljs.core]}
         :react
         {:entries ["react" "react-dom" "create-react-class"]
          :depends-on #{:base}}
         :reagent
         {:entries [reagent.core]
          :depends-on #{:base :react}}}

        :js-options
        {:js-provider :require
         :packages
         {"react" {}
          "react-dom" {}
          "create-react-class" {}}}}
      {})
    (catch Exception ex
      (errors/user-friendly-error ex))))

(deftest test-just-core
  (api/release*
    '{:build-id :just-core
      :target :browser

      :output-dir "target/test-just-core/js"
      :asset-path "/js"

      :compiler-options
      {:language-in :ecmascript5}

      :modules
      {:base
       {:entries [cljs.core]}}
      }
    {}))


(deftest test-single-entry
  (let [{:keys [build-sources build-macros] :as state}
        (api/compile*
          '{:build-id :single-entry
            :target :browser

            :output-dir "target/test-single-entry/js"
            :asset-path "/js"

            :modules
            {:base
             {:entries [demo.browser]}}}
          {})]
    ;; (prn :build-sources)
    ;; (pprint build-sources)

    (prn :build-macros)
    (pprint build-macros)
    ))

(deftest test-build-with-foreign
  (try
    (api/compile*
      '{:build-id :browser
        :target :browser

        :output-dir "target/test-build-with-foreign/js"
        :asset-path "/js"

        :modules
        {:base
         {:entries [cljs.core]}

         :react
         {:entries [cljsjs.react]
          :depends-on #{:base}}

         :test
         {:entries [shadow.markup.react]
          :depends-on #{:react :base}}}

        :js-options
        {:js-provider :closure}}
      {})
    (catch Exception ex
      (errors/user-friendly-error ex))))

(defn print-js [state provides]
  (doseq [provide provides]
    (let [rc
          (data/get-source-by-provide state provide)

          {:keys [js] :as output}
          (data/get-output! state rc)]

      (println js)
      )))

(deftest test-js-reserved-property-access
  (let [state
        (api/compile*
          '{:build-id :dot-default
            :target :browser

            :output-dir "target/test-dot-default/js"
            :asset-path "/js"

            :build-options
            {:cache-level :jars}

            :modules
            {:base {:entries [demo.closure]}}

            :devtools
            {:console-support false
             :enabled false}}
          {})]


    (print-js state '[demo.closure
                      module$src$dev$demo$es6])

    ))

(def polyfill-config
  '{:build-id :browser
    :target :browser

    :output-dir "target/test-build-with-polyfill/js"
    :asset-path "/js"

    :modules
    {:test {:entries ["/test/es6/polyfill.js"]}}

    :js-options
    {:js-provider :closure}

    :devtools
    {:console-support false}})

(deftest test-compile-with-polyfill
  (try
    (let [state
          (api/compile* polyfill-config {:debug true})]

      (is (seq (get-in state [:polyfill-js])))
      #_(println "POLYFILL")
      #_(-> (get-in state [:polyfill-js])
            (println))

      (println "POLYFILL")
      (println (get-in state [:polyfill-js]))

      (println "CODE")
      (-> (get-in state [:output])
          (vals)
          (last)
          (:js)
          (println)))

    (catch Exception ex
      (errors/user-friendly-error ex))))

(deftest test-release-with-polyfill
  (try
    (let [state
          (api/release* polyfill-config {})]

      (-> (get-in state [::closure/modules])
          (last)
          (:output)
          (println)))

    (catch Exception ex
      (errors/user-friendly-error ex))))


(deftest test-js-only-build
  (try
    (let [{:keys [build-sources] :as state}
          (api/compile*
            '{:build-id :js-only
              :target :browser

              :output-dir "target/js-only/js"
              :asset-path "/js"
              :compiler-options
              {:language-out :ecmascript5}

              :modules
              {:test {:entries ["fabric" #_"/test/cjs/entry.js"]}}

              :devtools
              {:enabled false
               :console-support false}
              :js-options
              {:js-provider :shadow
               :pretty-print true}}
            {:debug true})]

      (pprint (map second build-sources)))

    (catch Exception ex
      (errors/user-friendly-error ex))))


(deftest test-babel-transform
  (try
    (let [build-state
          (api/compile*
            '{:build-id :babel-transform
              :target :browser

              :output-dir "target/babel-transform/js"
              :asset-path "/js"
              :compiler-options
              {:language-out :ecmascript5}

              :modules
              {:test {:entries ["/test/es6/babel.js"]}}

              :js-options
              {:js-provider :shadow}}
            {:debug true})]

      )

    (catch Exception ex
      (errors/user-friendly-error ex))))


(deftest test-browser-build-with-js
  (try
    (let [{:keys [live-js-deps dead-js-deps js-entries] :as build-state}
          (api/release*
            '{:build-id :js-test
              :target :browser

              :output-dir "target/test-browser-js/js"
              :asset-path "/js"
              :compiler-options
              {:language-out :ecmascript5
               :pretty-print true
               :source-map true
               :optimizations :advanced}

              :modules
              {:test {:entries ["/test/cjs/a.js"]}}

              :js-options
              {:js-provider :shadow}

              :devtools
              {:enabled false
               :console-support false}}
            {})]

      (pprint (:build-sources build-state))
      (pprint live-js-deps)
      (pprint dead-js-deps)
      (pprint js-entries)

      (let [{:keys [output] :as module} (get-in build-state [::closure/modules 0])]
        (println output))

      #_(let [{:keys [js removed-requires actual-requires] :as output}
              (get-in build-state [:output [::npm/resource "node_modules/react/index.js"]])]
          (println js)
          (prn removed-requires)
          (prn actual-requires)))

    (catch Exception ex
      (errors/user-friendly-error ex))))

(deftest test-random-node-module
  (try
    (api/compile*
      '{:build-id :npm-package
        :target :browser

        :output-dir "target/test-node-module/js"
        :asset-path "/js"
        :compiler-options
        {:language-out :ecmascript5}

        ;; things that don't work
        ;; material-ui, because of bowser, invalid CJS wrapper
        ;; styled-components, because of stylis, invalid CJS wrapper
        ;; @atlaskit/navigation, because of
        ;; failed to resolve: ../build/jsVars from /Users/zilence/code/shadow-cljs/node_modules/@atlaskit/util-shared-styles/dist/es/index.js
        ;; the file is not available in the dist
        ;; "leaflet"
        ;; IllegalStateException: An enclosing scope is required for change reports but node BLOCK 26
        ;; @material/drawer
        ;; IllegalStateException: com.google.common.base.Preconditions.checkState (Preconditions.java:429
        :modules
        {:test {:entries [#_"jquery" ;; cant remove UMD
                          #_"animated/lib/targets/react-dom" ;; internal compiler error
                          #_"bootstrap" ;; expects global jQuery, wrapped IIFE, not properly converted
                          #_"shortid" ;; browser overrides
                          #_"js-nacl" ;; fs dependency
                          #_"readable-stream"
                          #_"material-ui/styles/getMuiTheme"
                          #_"foo"
                          #_cljs.tools.reader.reader-types
                          #_"react-vis"
                          #_"@material/animation"
                          ;; "react-markdown"
                          #_"pixi.js"
                          "viz.js"
                          ]}}

        :js-options
        {:js-provider :shadow}}
      {:debug true})
    (catch Exception ex
      (errors/user-friendly-error ex))))

(deftest test-build-read-cond
  (try
    (let [state
          (api/compile*
            '{:build-id :read-cond
              :target :node-script

              :output-to "target/test-build-read-cond.js"
              :main demo.read-cond/main

              :compiler-options
              {:reader-features #{:cljs/ssr}}}
            {})

          rc
          (data/get-source-by-provide state 'demo.read-cond)

          out
          (data/get-output! state rc)]

      (println (:js out)))
    (catch Exception ex
      (errors/user-friendly-error ex))))

(deftest test-build-rel-require
  (try
    (let [state
          (api/compile*
            '{:build-id :rel-require
              :target :browser
              :output-dir "target/test-build-rel-require/js"
              :modules {:main {:entries [demo.rel-require]}}}
            #_'{:build-id :rel-require
                :target :node-script

                :output-to "target/test-build-rel-require.js"
                :main demo.rel-require/main}
            {})

          rc
          (data/get-source-by-provide state 'demo.rel-require)

          out
          (data/get-output! state rc)]

      (println (:js out)))
    (catch Exception ex
      (errors/user-friendly-error ex))))

(deftest test-build-js-entry
  (try
    (let [{:keys [build-sources js-properties] :as state}
          (api/compile*
            '{:build-id :js-entry
              :target :browser
              :output-dir "target/js-entry/js"
              :modules {:main {:entries ["/demo/foo.js"]}}}
            {})]

      (pprint build-sources)
      (pprint js-properties)
      )
    (catch Exception ex
      (errors/user-friendly-error ex))))

(deftest test-closure-es6-import
  (try
    (let [{:keys [build-sources] :as state}
          (api/compile*
            '{:build-id :js-entry
              :target :browser
              :output-dir "target/closure-es6/js"
              :compiler-options
              {:pretty-print true
               :pseudo-names true}
              :modules {:main {:entries [goog "/test/es6"]}}
              :devtools {:enabled false :console-support false}}
            {})]

      )
    (catch Exception ex
      (errors/user-friendly-error ex))))

(deftest test-closure-es6-interop
  (try
    (let [{:keys [build-sources] :as state}
          (api/compile*
            '{:build-id :js-interop
              :target :node-test
              :ns-regexp "test.js-interop-test"
              :output-to "target/js-interop/test.js"
              :compiler-options
              {:pretty-print true
               :pseudo-names true}}
            {})]

      )
    (catch Exception ex
      (errors/user-friendly-error ex))))

(comment
  (api/compile :browser))

(deftest test-npm-check
  (api/check :npm))

(deftest test-cli-check
  (api/check :cli))

(deftest test-npm-module
  (api/compile :npm-module))

(deftest test-foreign
  (api/release :foreign {:source-maps true}))

(deftest test-node-test
  (api/compile :test-node)
  (let [{:keys [exit out err] :as result}
        (sh "node" "out/demo-test-node/script.js")]
    (println out)
    (println err)
    (is (zero? exit))))

(comment
  (defn load-from-disk [{:keys [public-dir build-sources] :as state}]
    (-> state
        (build-api/prepare-compile)
        (build-api/prepare-modules)
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
            build-sources)))))



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
          (-> (build-api/init)
              (build-api/merge-build-options
                {:public-dir (io/file "out" "demo-foreign" "js")
                 :asset-path "js"
                 :cache-level :off

                 ;; :externs-sources [(SourceFile/fromFile (io/file "tmp/test.externs.js"))]
                 })
              (build-api/merge-compiler-options
                {:optimizations :advanced
                 :pretty-print false
                 :pseudo-names false
                 :externs
                 ["shadow/api/externs.js"]
                 :closure-warnings
                 {:check-types :warning}
                 })
              (build-api/enable-source-maps)

              (build-api/configure-modules
                '{:main
                  {:entries [demo.prototype demo.foreign]
                   :depends-on #{}
                   :prepend-js
                   "goog.nodeGlobalRequire = function(/** String */ name) {};"}})
              ;; (load-from-disk)
              (build-api/analyze-modules)
              (build-api/compile-sources)
              ;; (api/flush-unoptimized) ;; doesn't work
              (build-api/check)
              ;; (api/flush-modules-to-disk)
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


(deftest test-open-file-command
  (let [data
        {:file "/some/abs-file/somefile.js"
         :line 123
         :column 3}]

    (prn (util/make-open-args data ["idea" :pwd :file "--line" :line]))
    (prn (util/make-open-args data ["emacsclient" ["%s:%s:%s" :file :line :column]]))
    ))

(deftest test-open-file-command-wsl
  (let [data
        {:file "/mnt/c/Users/someone/code/foo/bar.cljs"
         :line 123
         :column 3}]

    (prn (util/make-open-args data ["idea" :wsl-pwd :wsl-file "--line" :line]))

    ))

(deftest test-build-info
  (let [{info :shadow.build/build-info :as x}
        (api/compile*
          '{:build-id :test
            :target :browser
            :output-dir "target/test-build-info/js"
            :asset-path "/js"
            :modules
            {:main {:entries [demo.browser]}}}
          {})]

    (pprint info)

    ))