(ns shadow.build.esm-test
  (:require
   [clojure.test :refer (deftest is)]
   [shadow.build :as build]
   [shadow.build.api :as build-api]
   [shadow.build.data :as data]
   [shadow.build.classpath :as cp]
   [shadow.cljs.util :as util]
   [shadow.cljs.devtools.server.worker.impl :as worker-impl]
   [clojure.core.async :as async-chan]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import (java.nio.file Files)))

(defn create-temp-dir! [prefix]
  (.toFile (Files/createTempDirectory prefix (into-array java.nio.file.attribute.FileAttribute []))))

(defn delete-dir! [^java.io.File file]
  (when (.isDirectory file)
    (doseq [child (.listFiles file)]
      (delete-dir! child)))
  (io/delete-file file))

(deftest test-esm-integrated-compile
  (let [temp-dir (create-temp-dir! "esm-integrated-")
        out-dir (-> (io/file temp-dir "out") (.getCanonicalFile))
        cache-dir (-> (io/file temp-dir "cache") (.getCanonicalFile))]
    (try
      (let [cp-service (-> (cp/start cache-dir)
                           (cp/index-classpath))
            build-config {:target :esm
                          :build-id :test-esm
                          :output-dir (.getAbsolutePath out-dir)
                          :modules {:main {:entries ['demo.esm.d]}}}
            
            initial-state (-> (build-api/init)
                              (assoc :mode :dev)
                              (assoc :shadow.cljs.devtools.server.worker.impl/compile-attempt 0)
                              (build-api/with-logger (util/log-collector))
                              (build-api/with-classpath cp-service)
                              (build/configure :dev build-config {}))
            
            worker-state {:build-id :test-esm
                          :build-state initial-state
                          :channels {:output (async-chan/chan 100)}}]
        
        (with-redefs [worker-impl/>!!output (fn [ws msg] ws)
                      worker-impl/send-to-runtimes (fn [ws msg] ws)
                      worker-impl/build-find-hooks (fn [bs] bs)]
          
          (let [final-worker-state (worker-impl/build-compile worker-state)
                final-state (:build-state final-worker-state)
                get-result-by-id (fn [final-state id]
                                   (let [src (data/get-source-by-id final-state id)
                                         possible-files [(io/file out-dir (:output-name src))
                                                         (io/file out-dir "cljs-runtime" (:output-name src))]
                                         js-file (first (filter #(.exists %) possible-files))]
                                     js-file))]
            
            (is (some? final-state) "Build state should exist after compile")
            
            (when final-state
              (let [d-id (data/get-source-id-by-provide final-state 'demo.esm.d)
                    cs-id (data/get-source-id-by-provide final-state 'clojure.string)
                    d-js (and d-id (get-result-by-id final-state d-id))
                    cs-js (and cs-id (get-result-by-id final-state cs-id))]
                (is (some? d-id) "demo.esm.d should be found in state")
                (is (some? cs-id) "clojure.string should be found in state")
                (is (some? d-js) "Output JS for demo.esm.d should exist")
                (is (some? cs-js) "Output JS for clojure.string should exist")
                (when d-js
                  (let [content (slurp d-js)]
                    (is (str/includes? content "import \"./cljs_env.js\";") 
                        "Missing cljs_env import")
                    (is (str/includes? content "import \"./clojure.string.js\";")
                        "Missing clojure.string import")
                    ))
                (when cs-js
                  (let [content (slurp cs-js)]
                    (is (str/includes? content "import \"./cljs_env.js\";")
                        "Missing cljs_env import")
                    (is (str/includes? content "import \"./goog.string.string.js\";")
                        "Missing goog.string.string import")
                    (is (str/includes? content "import \"./goog.string.stringbuffer.js\";")
                        "Missing goog.string.stringbuffer import")
                    )))))))
      (finally
        (delete-dir! temp-dir)))))