(ns shadow.build.api-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [cljs.analyzer :as cljs-ana]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.test-util :refer :all]
            [shadow.build.api :as api]
            [shadow.build.npm :as npm]
            [shadow.build.classpath :as cp]
            [shadow.build.js-support :as js-support]
            [shadow.build.resolve :as res]
            [shadow.cljs.util :as util]
            [shadow.build.modules :as modules]
            [shadow.build.cljs-bridge :as cljs-bridge]
            [shadow.build.warnings :as warnings]
            [shadow.build.compiler :as impl]
            [shadow.build.macros :as macros]
            [shadow.build.data :as data]
            [shadow.cljs.repl :as repl])
  (:import (java.util.concurrent Executors)))

(deftest test-compile

  (let [npm
        (npm/start)

        cache-root
        (io/file "target" "foo")

        exec
        (Executors/newFixedThreadPool 4)

        cp
        (-> (cp/start cache-root)
            (cp/index-classpath))

        output-dir
        (io/file "target" "foo-output")

        log
        (util/log-collector)]

    (try
      (let [build-state
            (-> (api/init)
                (api/with-logger log)
                (api/with-cache-dir cache-root)
                (api/with-classpath cp)
                (api/with-npm npm)
                (api/with-executor exec)
                (api/with-compiler-options
                  {:optimizations :advanced})
                (api/with-build-options
                  {:output-dir output-dir})
                (api/configure-modules
                  '{:core {:entries [cljs.core cljs.spec.alpha]}
                    :main {:entries [demo.browser clojure.spec.alpha]
                           :depends-on #{:core}}})
                ;;(api/prepare)
                ;;(modules/prepare)
                (api/analyze-modules)
                ;; (api/compile-sources)
                ;; (api/optimize)
                ;; (api/flush-unoptimized)
                )]

        (doseq [src-id (get-in build-state [:build-sources])]
          (let [{:keys [provides]} (data/get-source-by-id build-state src-id)]
            (prn [:x src-id provides])
            ))
        (wide-pprint @log))

      (finally
        (.shutdownNow exec)))

    ;; (is (zero? (count (warnings/get-warnings-for-build build-state))))
    ;; (warnings/print-warnings-for-build build-state)

    ;; (wide-pprint (::modules/module-order build-state))
    ;; (wide-pprint (::modules/modules build-state))
    ;; (wide-pprint (:build-sources build-state))


    (npm/stop npm)
    (cp/stop cp)
    ))

(defn test-build []
  (let [npm
        (npm/start)

        cache-root
        (io/file "target" "test-build")

        cp
        (-> (cp/start cache-root)
            (cp/index-classpath))

        output-dir
        (io/file "target" "test-build" "out")

        log
        (util/log-collector)]

    (-> (api/init)
        (api/with-cache-dir (io/file cache-root "cache"))
        (api/with-classpath cp)
        (api/with-npm npm)
        (api/with-logger log))
    ))


(deftest test-find-affected
  (let [build-state
        (-> (test-build)
            (api/add-sources-for-entries '[demo.browser
                                           demo.browser-extra]))

        affected-by-macros
        (api/find-resources-using-macros build-state
          '#{demo.browser})

        affected
        (api/find-resources-affected-by build-state
          affected-by-macros)]

    (is (= 1 (count affected-by-macros)))
    (is (= 2 (count affected)))

    (pprint affected-by-macros)
    (pprint affected)
    #_(->> build-state
           (:sources)
           (keys)
           (pprint))
    ))

(deftest test-with-repl
  (let [{:keys [repl-state] :as build-state}
        (-> (test-build)
            (assoc-in [:js-options :js-provider] :require)
            (repl/prepare)
            ;; (repl/process-input "(js/alert 1)")
            ;; (repl/process-input "(require '[goog.string :as x])")
            ;; (repl/process-input "(require '[goog.string :as y])")
            ;; (repl/process-input "(require '[\"react\" :as x])")
            ;; (repl/process-input "(require '[\"react\" :as y])")
            ;; (repl/process-input "(require 'clojure.string)")
            ;; (repl/process-input "(in-ns 'clojure.string)")
            (repl/process-input "(ns foo.bar)")
            )]
    (pprint repl-state)
    #_(-> repl-state :repl-actions last (pprint))
    ))


