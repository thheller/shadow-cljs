(ns shadow.cljs.foreign-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [clojure.edn :as edn]
            [shadow.cljs.closure :as closure]
            [cljs.closure]
            [clojure.java.io :as io]
            [shadow.cljs.util :as util]
    ;; too much util ...
            [shadow.cljs.devtools.server.util :as server-util]
            [clojure.string :as str])
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

(defn create-foreign-index [foreign-dir foreign-requires]
  {:pre [(every? string? foreign-requires)]}
  (let [index-file
        (io/file foreign-dir "index.js")

        requires
        (->> foreign-requires
             (map #(format "require('%s');" %))
             (str/join "\n"))

        index-in
        (-> (io/resource "shadow/cljs/npm/index-invoke.template")
            (slurp)
            (str/replace "PWD" (pr-str (.getAbsolutePath (io/file ""))))
            (str/replace "FILE" (pr-str (.getAbsolutePath index-file))))]

    (io/make-parents index-file)

    (spit index-file requires)

    (let [{:keys [exit err out] :as result}
          (server-util/launch ["node"] {:in index-in})

          _ (prn [:result result])
          data
          (cond
            (= 2 exit)
            (throw (ex-info "missing shadow-cljs npm package" {}))

            (= 1 exit)
            (throw (ex-info "failed to create js-index" (assoc result :tag ::js-index)))

            (zero? exit)
            (edn/read-string out))]

      (pprint (dissoc data :files))
      )))

(deftest test-foreign-indexer
  (let [foreign-dir
        (io/file "target/foreign")

        foreign-idx
        (create-foreign-index foreign-dir ["react" "react-dom" "react-dom/server" "unknown" "fs" "./src/test/foo.js"])
        ]))
