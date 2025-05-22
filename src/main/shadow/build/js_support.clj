(ns shadow.build.js-support
  (:require
    [shadow.cljs.util :as util]
    [shadow.build.data :as data]
    [clojure.string :as str]
    [shadow.build.npm :as npm]
    [shadow.build.classpath :as cp])
  (:import (com.google.javascript.jscomp.deps ModuleNames)))

;; https://cdn.pika.dev/preact@^10.0.0
;; ^ not replaced by ModuleNames/fileToModuleName
;; not that anyone should ever use version ranges in an import ...
;; should have probably used munge ...
(defn munge-require [require]
  (-> require
      (str/replace #"=" "_EQ_")
      (str/replace #"\>" "_GT_")
      (str/replace #"\<" "_LT_")
      (str/replace #"\^" "_CARET_")
      (str/replace #"\~" "_TILDE_")
      (str/replace #"#" "_HASH_")
      (ModuleNames/fileToModuleName)))

(comment
  (munge-require "npm:stripe@^11.16")
  (munge-require "npm:stripe@~11.16")
  (munge-require "npm:stripe@~>=11.16"))

(defn shim-require-resource
  [state js-require]
  (let [js-ns-alias
        (-> (munge-require js-require)
            (->> (str "shadow.js.shim."))
            (symbol))

        ;; since we can't stop closure from rewriting require("react") even when it shouldn't
        ;; we create a second alias var that it replaces the require with a var we actually created
        commonjs-ns
        (symbol (munge-require (str js-ns-alias)))

        name
        (str js-ns-alias ".js")

        require-fn
        (or (and (= :external (get-in state [:js-options :js-provider])) "shadow$bridge")
            (get-in state [:js-options :require-fn] "require"))]

    {:resource-id [::require js-require]
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
     :deps '[goog shadow.js]
     ;; for :npm-module support since we don't have a property to export
     ;; but need to export the entire "ns" which is just the result of require
     :export-self true
     ;; we emit this as a goog.provide so it the same code can be used for :advanced
     ;; as it won't touch the require call unless specifically told to
     :source (str "goog.provide(\"" js-ns-alias "\");\n"
                  "goog.provide(\"" commonjs-ns "\");\n"
                  js-ns-alias " = shadow.js.nativeProvides[\"" js-require "\"] = " require-fn "(\"" js-require "\");\n"
                  ;; FIXME: this default business is annoying
                  commonjs-ns ".default = " js-ns-alias ";\n"
                  )}
    ))

;; (:require ["some$nested.access" :as sugar])
(defn shim-require-sugar-resource
  [require-from js-require was-symbol?]
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
                 (munge-require (str prefix "$" suffix)))
            (symbol))

        resource-name
        (str js-ns-alias ".js")

        dep
        (if was-symbol?
          (symbol prefix)
          prefix)]

    {:resource-id [::require js-ns-alias]
     :resource-name resource-name
     :output-name (util/flat-js-name resource-name)
     ::split-require true
     :type :goog
     :cache-key [js-ns-alias resource-name]
     :last-modified 0
     :ns js-ns-alias
     :provides #{js-ns-alias}
     :requires #{}
     :deps ['goog dep]
     :export-self true
     :source-fn
     (fn [state]
       (let [{:keys [ns type] :as rc}
             (if was-symbol?
               (data/get-source-by-provide state dep)
               (let [alias (data/get-string-alias state js-ns-alias prefix)]
                 (data/get-source-by-provide state alias)))]

         (str "goog.provide(\"" js-ns-alias "\");\n"
              (case type
                :shadow-js
                (str js-ns-alias " = " (npm/shadow-js-require rc false)
                     ;; (:require ["some$foo.bar"]) emitting require("some")["foo"]["bar"]
                     ;; FIXME: should this generate externs instead?
                     (->> (str/split suffix #"\.")
                          (map #(str "[\"" % "\"]"))
                          (str/join ""))
                     ";\n")

                ;; plain suffix for sources going through :advanced
                (str js-ns-alias " = " (or ns dep) "." suffix ";\n"))
              )))}))

(defn shim-import-resource
  [{:shadow.build/keys [mode] :as state} js-name]
  (let [import-alias
        (-> (str "esm_" (munge-require js-name))
            (str/replace "module$" "import$")
            (symbol))

        fake-ns
        (if (= :release mode)
          import-alias
          (symbol (str "shadow.esm." import-alias)))

        resource-name
        (str fake-ns ".js")

        import
        (if (str/starts-with? js-name "esm:")
          (subs js-name 4)
          js-name)]

    {:resource-id [::require js-name]
     :resource-name resource-name
     :output-name resource-name
     :type :goog
     :cache-key [resource-name]
     :last-modified 0
     ::import-shim true
     :js-import import
     :js-alias fake-ns
     :import-alias import-alias
     :ns fake-ns
     :provides #{fake-ns}
     :requires #{}
     :virtual true ;; prevents file from going through convert-goog, which wouldn't like the import
     :deps (cond-> ['goog] (= :dev mode) (conj 'shadow.js))
     :source
     (if (= :release mode)
       ;; in release mode we are hiding imports from the closure compiler and run the ShadowESMImports pass to insert them after optimizations
       ""

       ;; in dev this is fine
       (str "import * as " import-alias " from \"" import "\";\n"
            "goog.provide(\"" fake-ns "\");\n"
            "shadow.js.nativeProvides[\"" fake-ns "\"] = " import-alias ";\n"
            fake-ns " = " import-alias ";\n"))}))