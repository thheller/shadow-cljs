(ns shadow.build.js-support
  (:require [shadow.build.resource :as rc]
            [shadow.cljs.util :as util]
            [clojure.java.io :as io]
            [shadow.build.data :as data]
            [shadow.build.closure :as closure])
  (:import (com.google.javascript.jscomp.deps ModuleNames)))

(defn shim-require-resource
  ([state js-require]
    (shim-require-resource state js-require js-require))
  ([state js-name js-require]
   (let [js-ns-alias
         (-> (ModuleNames/fileToModuleName js-name)
             (->> (str "shadow.js.shim."))
             (symbol))

         register-shadow-js
         (= :shadow (get-in state [:js-options :js-provider]))

         ;; since we can't stop closure from rewriting require("react") even when it shouldn't
         ;; we create a second alias var that it replaces the require with a var we actually created
         commonjs-ns
         (symbol (ModuleNames/fileToModuleName (str js-ns-alias)))

         name
         (str js-ns-alias ".js")

         require-fn
         (get-in state [:js-options :require-fn] "require")]

     {:resource-id [::require js-name]
      :resource-name name
      :output-name (util/flat-js-name name)
      :type :goog
      :cache-key [js-ns-alias name require-fn]
      :last-modified 0
      ::require-shim true
      :js-require js-require
      :ns js-ns-alias
      :provides #{js-ns-alias commonjs-ns}
      :requires #{}
      :deps (-> '[goog]
                (cond->
                  register-shadow-js
                  (conj 'shadow.js)))
      ;; for :npm-module support since we don't have a property to export
      ;; but need to export the entire "ns" which is just the result of require
      :export-self true
      ;; we emit this as a goog.provide so it the same code can be used for :advanced
      ;; as it won't touch the require call unless specifically told to
      :source (str "goog.provide(\"" js-ns-alias "\");\n"
                   "goog.provide(\"" commonjs-ns "\");\n"
                   js-ns-alias " = " require-fn "(\"" js-require "\");\n"
                   (when register-shadow-js
                     (str "shadow.js.add_native_require(\"" js-ns-alias "\", " js-ns-alias ");\n"))
                   ;; FIXME: this default business is annoying
                   commonjs-ns ".default = " js-ns-alias ";\n"
                   )}
     )))