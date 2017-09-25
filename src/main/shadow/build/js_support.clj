(ns shadow.build.js-support
  (:require [shadow.build.resource :as rc]
            [shadow.cljs.util :as util]
            [clojure.java.io :as io]
            [shadow.build.data :as data]
            [shadow.build.closure :as closure])
  (:import (java.io File StringWriter)
           (com.google.javascript.jscomp.deps ModuleNames ModuleLoader$ResolutionMode)
           (com.google.javascript.jscomp DiagnosticGroups CheckLevel ShadowAccess SourceFile JsAst NodeTraversal)
           (com.google.javascript.rhino IR)
           (shadow.build.closure NodeEnvTraversal$Remove ReplaceRequirePass)))

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

(defn convert-sources
  "takes a list of :npm sources and rewrites them to closure JS"
  [{:keys [project-dir npm] :as state} sources]
  (util/with-logged-time [state {:type ::convert
                                 :num-sources (count sources)}]

    ;; FIXME: this should do caching but Closure needs all files when compiling
    ;; cannot compile one file at a time with this approach
    ;; CLJS does one at a time but that has other issues
    ))

(defn remove-node-env-branches [node cc node-env]
  (let [pass (NodeEnvTraversal$Remove. node-env cc)]
    (NodeTraversal/traverseEs6 cc node pass)))

(defn replace-requires [node cc replacements]
  (let [pass (ReplaceRequirePass. cc replacements)]
    (NodeTraversal/traverseEs6 cc node pass)))

(defn compile-sources [{:keys [mode] :as state} sources]
  (let [cc
        (closure/make-closure-compiler)

        ;; FIXME: are there more options we should take from the user?
        co-opts
        {:pretty-print true
         :source-map true
         :language-in :ecmascript-next
         :language-out :ecmascript3}

        co
        (doto (closure/make-options)
          (closure/set-options co-opts))

        co
        (doto co
          ;; we only transpile, no optimizing or type checking
          ;; actually we don't even transpile, just replacing requires
          (.setSkipNonTranspilationPasses true)

          (.setWarningLevel DiagnosticGroups/NON_STANDARD_JSDOC CheckLevel/OFF)

          (.setPreserveTypeAnnotations true)

          (.setNumParallelThreads (get-in state [:compiler-options :closure-threads] 1)))

        _
        (.init cc [] [] co)

        source-map
        (.getSourceMap cc)]

    (reduce
      (fn [state {:keys [resource-id resource-name output-name source ns] :as src}]
        (.reset source-map)

        (let [source-file
              (SourceFile/fromCode resource-name
                (str "shadow.npm.register(\"" ns "\", function(module,exports) {\n"
                     source
                     "\n});\n"))

              source-ast
              (JsAst. source-file)

              source-node
              (doto (.getAstRoot source-ast cc)
                (remove-node-env-branches cc (if (= :release mode)
                                               "production"
                                               "development"))
                (replace-requires cc (get-in state [:str->sym ns])))


              js
              (try
                (ShadowAccess/nodeToJs cc source-map source-node)
                (catch Exception e
                  (throw (ex-info (format "failed to generate JS for \"%s\"" name) {:name name} e))))

              sw
              (StringWriter.)

              ;; for sourcesContent
              _ (.addSourceFile source-map source-file)
              _ (.appendTo source-map sw output-name)

              sm-json
              (.toString sw)

              output
              {:resource-id resource-id
               :js js
               :source-map-json sm-json}]

          (assoc-in state [:output resource-id] output)))
      state
      sources)))