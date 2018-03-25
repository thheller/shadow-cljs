(ns shadow.cljs.repl-test
  (:require [clojure.test :refer (deftest)]
            [clojure.java.io :as io]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.repl :as repl]
            [shadow.build.api :as api]
            [shadow.build.npm :as npm]
            [shadow.build.classpath :as cp]
            [shadow.cljs.devtools.errors :as errors]))

(defn basic-repl-setup []
  (let [npm
        (npm/start {})

        cache-root
        (io/file "target" "test-repl")

        cp
        (-> (cp/start cache-root)
            (cp/index-classpath))

        output-dir
        (io/file "target" "test-repl" "out")]

    (-> (api/init)
        (api/with-cache-dir (io/file cache-root "cache"))
        (api/with-classpath cp)
        (api/with-build-options
          {:output-dir output-dir})
        (api/with-npm npm)
        (repl/prepare)
        )))


(deftest test-repl-load-file
  (let [abs-file
        (-> (io/file "src" "dev" "demo" "repl.cljs")
            (.getAbsolutePath))

        {:keys [repl-state dead-js-deps] :as state}
        (-> (basic-repl-setup)
            (api/with-js-options {:js-provider :shadow})
            (repl/repl-load-file* {:file-path abs-file :source "(ns demo."})
            (repl/process-input (str "(load-file \"" abs-file "\")")))]

    (pprint repl-state)
    ))

(deftest test-repl-load-file-not-on-disk-yet
  (let [abs-file
        (-> (io/file "src" "dev" "demo" "not_on_disk.cljs")
            (.getAbsolutePath))

        {:keys [repl-state dead-js-deps] :as state}
        (-> (basic-repl-setup)
            (api/with-js-options {:js-provider :shadow})
            ;; nREPL also provides the actual source code
            (repl/repl-load-file* {:file-path abs-file :source "(ns demo.not-on-disk (:require [\"react\"]))"}))]

    (pprint repl-state)
    ))

(deftest test-repl-ns-repeated
  (let [test-ns-str
        "(ns demo.thing (:require [\"react\" :as r] [clojure.string :as str] [clojure.pprint]))"

        {:keys [repl-state] :as state}
        (-> (basic-repl-setup)
            (api/with-js-options {:js-provider :shadow})
            (repl/process-input test-ns-str)
            (repl/process-input test-ns-str))]

    (pprint repl-state)))

(deftest test-repl-require-in-ns
  (let [{:keys [repl-state] :as state}
        (-> (basic-repl-setup)
            (api/with-js-options {:js-provider :require})
            (repl/process-input "(require 'demo.script)")
            (repl/process-input "(in-ns 'demo.script)"))]

    (pprint repl-state)))


(deftest test-repl-string-require
  (let [{:keys [repl-state] :as state}
        (-> (basic-repl-setup)
            (api/with-js-options {:js-provider :shadow})
            (repl/process-input "(require '[\"auth0-js\" :as x])")
            (repl/process-input "x"))]

    (pprint repl-state)))
