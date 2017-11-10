(ns shadow.cljs.deps-test
  (:require [clojure.test :refer (deftest is)]
            [clojure.pprint :refer (pprint)]

            [clojure.tools.deps.alpha :as deps]
            [clojure.tools.deps.alpha.util.maven :as mvn-util]
            [clojure.tools.deps.alpha.providers.local]
            [clojure.tools.deps.alpha.providers.maven]
            [clojure.tools.deps.alpha.providers :as providers]
            #_ [cemerick.pomegranate.aether :as pom])
  (:import (clojure.lang PersistentQueue)))

(deftest test-tools-deps
  (pprint (deps/resolve-deps
            {:deps '{thheller/shadow-cljs {:mvn/version "2.0.70"}}
             :mvn/repos mvn-util/standard-repos}
            {})))

(comment
  (deftest test-pomegranate

    (pprint (pom/resolve-dependencies
              :repositories
              {"central" "https://repo1.maven.org/maven2/"
               "clojars" "https://clojars.org/repo/"}
              :coordinates
              [['thheller/shadow-cljs "2.0.59"]]))))

