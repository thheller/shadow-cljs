(ns shadow.cljs.foreign-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [clojure.edn :as edn]
            [shadow.cljs.closure :as closure]
            [cljs.closure]
            [clojure.java.io :as io]
            [shadow.cljs.util :as util])
  (:import (com.google.javascript.jscomp CompilerOptions SourceFile DiagnosticGroup DiagnosticGroups CheckLevel)
           (com.google.javascript.jscomp.deps ModuleLoader$ResolutionMode)))

(deftest test-foreign-bundler
  (let [index
        (-> (slurp "tmp/index.edn")
            (edn/read-string))

        source-files
        (->> (:files index)
             (map #(SourceFile/fromCode % (slurp %)))
             (into []))

        cc
        (closure/make-closure-compiler)

        co
        (doto (cljs.closure/make-convert-js-module-options {:pretty-print true})
          (.setWarningLevel DiagnosticGroups/NON_STANDARD_JSDOC CheckLevel/OFF)
          (.setProcessCommonJSModules true)
          (.setTransformAMDToCJSModules true))

        externs
        closure/default-externs

        result
        (.compile cc externs source-files co)

        _ (assert (.success result))

        sources
        (.toSourceArray cc)

        files
        (->> (interleave (:files index) sources)
             (partition 2)
             (into []))

        output-dir
        (io/file "target/foreign")]

    (io/make-parents (io/file output-dir "foo.txt"))

    (assert (= (count sources) (count source-files)))

    (doseq [[file source] files]
      (prn file)
      (let [output-to (io/file output-dir (util/flat-filename file))]
        (spit output-to source)))

    ))
