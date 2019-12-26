(ns shadow.cljs.closure-test
  (:require [clojure.test :refer (deftest is)]
            [clojure.pprint :refer (pprint)]
            [shadow.build.closure :as closure]
            [shadow.build.data :as data]
            [clojure.java.io :as io])
  (:import (com.google.javascript.jscomp ShadowAccess ClosureRewriteModule CompilerOptions CompilerOptions$LanguageMode)
           [com.google.javascript.jscomp.transpile BaseTranspiler]
           [java.net URI]))


(deftest test-get-externs-properties
  (let [cc
        (data/make-closure-compiler)

        co
        (closure/make-options)

        externs
        @closure/default-externs

        result
        (.compile cc externs [] co)]

    (prn (ShadowAccess/getExternProperties cc))
    ))

(deftest test-transpile-goog-module
  (let [transpiler
        BaseTranspiler/ES5_TRANSPILER

        code
        (slurp (io/resource "goog/loader/activemodulemanager.js"))

        result
        (.transpile transpiler (URI. "/foo/bar.js") code)]

    (println (.transpiled result))
    ;; (prn result)
    ))