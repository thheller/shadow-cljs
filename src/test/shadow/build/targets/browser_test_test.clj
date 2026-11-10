(ns shadow.build.targets.browser-test-test
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer (deftest is testing)]
    [shadow.build :as build]
    [shadow.build.api :as build-api]
    [shadow.build.modules :as modules]
    [shadow.build.targets.browser-test :as browser-test]
    [shadow.build.test-util :as tu])
  (:import [java.nio.file Files]))

(deftest esm-test-module
  (let [test-dir (.toFile (Files/createTempDirectory "shadow-browser-test" (make-array java.nio.file.attribute.FileAttribute 0)))
        index-html (io/file test-dir "index.html")]
    (try
      ;; Resolution and compiler fingerprints need a classpath. Keep this test
      ;; focused on refreshing the module config across successive builds.
      (with-redefs [modules/analyze identity
                    tu/inject-extra-requires identity
                    tu/update-test-fingerprints identity]
        (doseq [mode [:dev :release]
                watch? [false true]]
          (testing (str mode ", watch=" watch?)
            (let [configured
                  (-> (build-api/init)
                      (assoc ::build/mode mode
                             ::build/stage :configure
                             ::build/config
                             {:build-id :browser-test
                              :test-dir (str test-dir)
                              :runner-ns 'custom.runner
                              :namespaces '[example.first-test]
                              :devtools {:preloads '[example.preload]
                                         :console-support false}})
                      (cond-> watch? (assoc :worker-info {}))
                      (browser-test/process))
                  resolved
                  (-> configured
                      (assoc ::build/stage :resolve)
                      (browser-test/process))
                  refreshed
                  (-> resolved
                      (assoc-in [::build/config :namespaces] '[example.second-test])
                      (browser-test/process))
                  module (get-in refreshed [::modules/config :test])
                  entries (:entries module)
                  startup (if (= mode :dev) :append :append-js)]
              (is (::build/esm configured))
              (is (true? (get-in configured [:compiler-options :load-tests])))
              (is (= 'custom.runner/init (:init-fn module)))
              (is (str/includes? (get module startup) "custom.runner.init();"))
              (is (= (get-in resolved [::modules/config :test startup])
                     (get module startup)))
              (is (= '[example.second-test] (::tu/test-namespaces refreshed)))
              (is (not-any? #{'example.first-test} entries))
              (is (= 1 (get (frequencies entries) 'example.second-test)))
              (is (< (.indexOf entries 'shadow.test.env)
                     (.indexOf entries 'example.second-test)))
              (is (= (= mode :dev) (boolean (some #{'example.preload} entries))))
              (is (= (and (= mode :dev) watch?)
                     (boolean (some #{'shadow.cljs.devtools.client.browser} entries))))))))

      (let [html (slurp index-html)]
        (is (str/includes? html "type=\"module\""))
        (is (str/includes? html "src=\"js/test.js\""))
        (is (str/includes? html "rel=\"stylesheet\""))
        (is (str/includes? html "href=\"css/test.css\""))
        (is (not (str/includes? html ".init()"))))

      (testing "stylesheet includes base styles and generated utility rules"
        (browser-test/flush-css {::build/config {:test-dir (str test-dir)}})
        (let [css (slurp (io/file test-dir "css" "test.css"))]
          (is (str/includes? css "--st-bg:#ffffff"))
          (is (str/includes? css "@media (prefers-color-scheme:dark)"))
          (is (str/includes? css ".shadow_test_browser_ui__"))))

      (testing "existing HTML is preserved"
        (spit index-html "custom test page")
        (browser-test/modify-config {::build/config {:test-dir (str test-dir)}})
        (is (= "custom test page" (slurp index-html))))
      (finally
        (doseq [file (reverse (file-seq test-dir))]
          (io/delete-file file))))))
