(ns shadow.undertow-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [shadow.undertow :as undertow])
  (:import [io.undertow.util HeaderMap HttpString]))

(def test-cookie "shadow-cljs/session=111222333; Max-Age=1295940; Path=/; HttpOnly; Secure; SameSite=Lax")

(deftest unset-secure-cookie-check
  (testing "Verifies that the unset-secure-cookie feature properly parses cookie strings"
    (let [headers
          (doto (HeaderMap.)
            (.put (HttpString. "set-cookie") test-cookie))]
      (is (str/includes? test-cookie "Secure;"))
      (undertow/unset-secure-cookie headers)
      (let [cookie (.get headers "set-cookie" 0)]
        (is (str/includes? cookie "HttpOnly;"))
        (is (not (str/includes? cookie "Secure;")))))))
