(ns shadow.build.js-support
  (:require [shadow.build.resource :as rc]
            [shadow.cljs.util :as util]
            [clojure.java.io :as io]
            [shadow.build.data :as data]
            [shadow.build.closure :as closure]
            [clojure.string :as str]
            [shadow.build.npm :as npm]
            [shadow.build.classpath :as cp])
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
         (or (and (= :external (get-in state [:js-options :js-provider])) "shadow$bridge")
             (get-in state [:js-options :require-fn] "require"))]

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

;; (:require ["some$nested.access" :as sugar])
(defn shim-require-sugar-resource
  [require-from js-require]
  (let [[prefix suffix]
        (str/split js-require #"\$" 2)

        ;; need to resolve (:require ["./foo.js$bar"]) since it may have different meanings
        ;; in other namespaces but use the same name
        prefix
        (if (util/is-relative? prefix)
          (str "/" (cp/resolve-rel-path (:resource-name require-from) prefix))
          prefix)

        js-ns-alias
        (-> (str "shadow.js.shim."
                 (ModuleNames/fileToModuleName (str prefix "$" suffix)))
            (symbol))

        ;; FIXME: should this generate externs instead?
        prop-access
        (->> (str/split suffix #"\.")
             (map #(str "[\"" % "\"]"))
             (str/join ""))

        resource-name
        (str js-ns-alias ".js")]

    {:resource-id [::require js-ns-alias]
     :resource-name resource-name
     :output-name (util/flat-js-name resource-name)
     :type :goog
     :cache-key [js-ns-alias resource-name]
     :last-modified 0
     :ns js-ns-alias
     :provides #{js-ns-alias}
     :requires #{}
     :deps ['goog prefix]
     :export-self true
     :source-fn
     (fn [state]
       (let [alias (data/get-string-alias state js-ns-alias prefix)
             {:keys [ns type] :as rc} (data/get-source-by-provide state alias)]

         (str "goog.provide(\"" js-ns-alias "\");\n"
              (case type
                :shadow-js
                (str js-ns-alias " = " (npm/shadow-js-require rc false) prop-access ";\n")

                (str js-ns-alias " = " ns prop-access ";\n"))
              )))}))