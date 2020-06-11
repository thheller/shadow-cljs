(ns shadow.cljs.devtools.server.npm-deps-test
  (:require [clojure.test :refer [deftest testing is]]
            [shadow.cljs.devtools.server.npm-deps :as sut]))


(deftest sever-intersects-test
  (testing "smoke-test for JS interop"
    (is (sut/semver-intersects "^1.0.0" "^1.1.0"))
    (is (sut/semver-intersects "github:foo" "github:foo"))
    (is (not (sut/semver-intersects ">=1.3.0" "1.2.0")))
    (is (not (sut/semver-intersects "^2.0.0" "^1.1.0")))))
