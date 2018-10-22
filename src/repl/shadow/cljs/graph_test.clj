(ns shadow.cljs.graph-test
  (:require
    [clojure.test :refer (deftest is)]
    [clojure.pprint :as pp]
    [shadow.cljs.model :as m]
    [shadow.cljs.devtools.graph :as graph]
    [shadow.cljs.devtools.server.runtime :as rt]))

(defn pprint [x]
  (binding [*print-namespace-maps* false]
    (pp/pprint x)))

(defn dump [query]
  (let [rt (rt/get-instance!)]
    (pprint (graph/parser rt query))))

(deftest test-resource-for-name
  (dump '[{(::m/resource-for-ns {:ns demo.browser})
           [::m/resource-info]}]))

(deftest test-resource-by-id
  (dump '[{[::m/resource-id [:shadow.build.classpath/resource "clojure/string.cljs"]]
           [::m/resource-info]}]))

(deftest test-resource-by-name
  (dump '[{[::m/resource-name "clojure/string.cljs"]
           [::m/resource-info]}]))

(deftest test-build-configs
  (dump '[::m/build-configs]))

(deftest test-build-by-id
  (dump '[{[::m/build-id :browser]
           [::m/build-config-raw]}]))

(deftest test-ns-info
  (dump '[{[::m/cljs-ns clojure.string]
           [::m/cljs-sources]}])
  (dump '[{[::m/cljs-ns clojure.string]
           [{::m/cljs-ns-defs
             [::m/cljs-def
              ::m/cljs-def-doc]}]}]))

(deftest test-classpath-query
  (dump '[(::m/classpath-query {:type #{:cljs}
                                :matching "demo/browser"})]))

(deftest test-build-deps-for-entry
  (dump '[{[::m/build-id :browser]
           [(::m/entry-deps {:entry demo.browser})]}]))
