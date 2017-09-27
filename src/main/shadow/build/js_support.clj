(ns shadow.build.js-support
  (:require [shadow.build.resource :as rc]
            [shadow.cljs.util :as util]
            [clojure.java.io :as io]
            [shadow.build.data :as data]
            [shadow.build.closure :as closure])
  (:import (com.google.javascript.jscomp.deps ModuleNames)))

(defn shim-require-resource [js-require]
  (let [js-ns-alias
        (-> (ModuleNames/fileToModuleName js-require)
            (symbol))

        name
        (str js-ns-alias ".js")]

    {:resource-id [::require js-require]
     :resource-name name
     :output-name (util/flat-js-name name)
     :type :goog
     :cache-key [js-ns-alias name]
     :last-modified 0
     :js-require js-require
     :ns js-ns-alias
     :provides #{js-ns-alias}
     :requires #{}
     :deps '[goog]
     ;; for :npm-module support since we don't have a property to export
     ;; but need to export the entire "ns" which is just the result of require
     :export-self true
     ;; we emit this as a goog.provide so it the same code can be used for :advanced
     ;; as it won't touch the require call unless specifically told to
     :source (str "goog.provide(\"" js-ns-alias "\");\n"
                  (str js-ns-alias " = require(\"" js-require "\");\n"))}
    ))