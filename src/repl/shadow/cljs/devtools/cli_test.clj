(ns shadow.cljs.devtools.cli-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.devtools.cli :as cli]
            [shadow.cljs.devtools.cli-opts :as cli-opts]))


(deftest test-no-args
  (let [args []

        {:keys [arguments options summary errors] :as parsed}
        (cli-opts/parse args)]

    (is (some? errors))
    (prn errors)
    ))

(deftest test-action-without-build
  (let [args ["compile"]

        {:keys [arguments options summary errors] :as parsed}
        (cli-opts/parse args)]

    (is (some? errors))
    (prn errors)
    ))

(deftest test-action-with-builds
  (let [args ["compile" "foo" "bar" "baz"]

        {:keys [arguments options summary errors action builds] :as parsed}
        (cli-opts/parse args)]

    (is (empty? errors))
    (is (= :compile action))
    (is (= ["foo" "bar" "baz"] builds))
    ))


(deftest test-add-dependency
  (let [args ["-d" "foo/bar:1.2.3" "compile" "cli"]

        {:keys [arguments options summary errors action builds] :as parsed}
        (cli-opts/parse args)]

    (is (empty? errors))
    (is (= '[[foo/bar "1.2.3"]] (:dependencies options)))
    ))

(deftest test-add-dependency
  (let [args ["-d" "foo/bar:1.2.3" "compile" "cli"]

        {:keys [arguments options summary errors action builds] :as parsed}
        (cli-opts/parse args)]

    (is (empty? errors))
    (is (= '[[foo/bar "1.2.3"]] (:dependencies options)))
    ))

(deftest test-add-dependency-new
  (let [args ["-d" "foo/bar:1.2.3" "compile" "cli"]

        result
        (cli-opts/parse-main-cli args)]

    (pprint result)

    ))

(deftest test-aliases
  (let [args ["-A:foo:bar:test" "compile" "cli"]

        {:keys [arguments options summary errors action builds] :as parsed}
        (cli-opts/parse args)]

    (is (empty? errors))
    (is (= ":foo:bar:test" (:aliases options)))
    ))
