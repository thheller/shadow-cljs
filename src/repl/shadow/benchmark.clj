(ns shadow.benchmark
  (:require [clojure.test :refer (deftest)]
            [shadow.build.api :as api]
            [clojure.java.io :as io]
            [shadow.cljs.util :as util]
            [shadow.build.classpath :as cp]
            [shadow.build.npm :as npm]
            [shadow.cljs.devtools.cli]
            [shadow.cljs.devtools.server]
            [clojure.string :as str])
  (:import (java.io FileOutputStream)))

(defn foo []
  (throw (ex-info "foo" {})))

(defn bar []
  1)

(defn test-build []
  (let [npm
        (npm/start {})

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

(defn -main []
  (go 5))

;; @cmal asked in #clojurescript clojurians whether
;; the keyword construction could be optimized by removing the fqn
;; since its identical to the name for unqualified keywords
;; result: small difference after gzip, extra overhead at runtime
;; this test also generates completely random keywords
;; real world will have some more overlap which should improve gzip
(deftest test-keyword-size-difference
  (let [dict
        (into [] "abcdefghijklmnopqrstuvwxyz1234567890")

        max
        (count dict)

        with-fqn
        #(str "new X(null, \"" % "\", \"" % "\", " (rand) ", null);")

        without-fqn
        #(str "new X(null, \"" % "\", " (rand) ", null);")]

    (with-open [out1 (FileOutputStream. "tmp/keywords-fqn.txt")
                out2 (FileOutputStream. "tmp/keywords-new.txt")]

      (dotimes [x 500]
        (let [gen-name
              (->> (range (+ 3 (rand-int 12)))
                   (map (fn [x] (nth dict (rand-int max))))
                   (str/join ""))]

          (.write out1 (.getBytes (with-fqn gen-name)))
          (.write out2 (.getBytes (without-fqn gen-name)))
          )))))

(comment
  (go 1)

  (go 10)

  )