(ns shadow.benchmark
  (:require [clojure.test :refer (deftest)]
            [shadow.build.api :as api]
            [clojure.java.io :as io]
            [shadow.cljs.util :as util]
            [shadow.build.classpath :as cp]
            [shadow.build.npm :as npm]))


(defn test-build []
  (let [npm
        (npm/start)

        cache-root
        (io/file "target" "test-build")

        cp
        (-> (cp/start cache-root)
            (cp/index-classpath))

        output-dir
        (io/file "target" "test-build" "out")]

    (-> (api/init)
        (api/with-cache-dir (io/file cache-root "cache"))
        (api/with-classpath cp)
        (api/with-build-options
          {:output-dir output-dir})
        (api/with-npm npm))))

(def test-state
  (delay
    (-> (test-build)
        (api/with-build-options {:cache-level :off})
        (api/configure-modules {:base {:entries ['cljs.core]}})
        (api/analyze-modules))))

(defn go [n]
  (dotimes [x n]
    (api/compile-sources @test-state)))

(deftest test-go
  (go 1))

(comment
  (go 1)

  (go 10)

  )