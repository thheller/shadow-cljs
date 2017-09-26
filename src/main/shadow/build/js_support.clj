(ns shadow.build.js-support
  (:require [shadow.build.resource :as rc]
            [shadow.cljs.util :as util]
            [clojure.java.io :as io]
            [shadow.build.data :as data]
            [shadow.build.closure :as closure])
  (:import (java.io File StringWriter)
           (com.google.javascript.jscomp.deps ModuleNames ModuleLoader$ResolutionMode)
           (com.google.javascript.jscomp DiagnosticGroups CheckLevel ShadowAccess SourceFile JsAst NodeTraversal CustomPassExecutionTime)
           (com.google.javascript.rhino IR)
           (shadow.build.closure ReplaceRequirePass NodeEnvInlinePass)))

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

(defn require-replacement-map [{:keys [str->sym sym->id] :as state}]
  (reduce-kv
    (fn [m ns require-map]
      (let [rc-id
            (data/get-source-id-by-provide state ns)
            {:keys [resource-name] :as rc}
            (data/get-source-by-id state rc-id)]
        (assoc m resource-name require-map)))
    {}
    str->sym))

(defn compile-sources
  "takes a list of :npm sources and rewrites them to closure JS"
  [{:keys [project-dir npm mode] :as state} sources]
  (util/with-logged-time [state {:type ::convert
                                 :num-sources (count sources)}]

    ;; FIXME: this should do caching but Closure needs all files when compiling
    ;; cannot compile one file at a time with this approach
    ;; CLJS does one at a time but that has other issues
    (let [source-files
          (->> (for [{:keys [resource-name ns file source] :as src} sources]
                 (SourceFile/fromCode resource-name
                   (str "shadow.npm.provide(\"" ns "\", function(module,exports) {\n"
                        source
                        "\n});\n")))
               (into []))

          source-file-names
          (into #{} (map #(.getName %)) source-files)

          ;; this includes all files (sources + package.json files)
          source-files-by-name
          (->> source-files
               (map (juxt #(.getName %) identity))
               (into {}))

          ;; this only includes resources we actually want output for
          resource-by-name
          (->> sources
               (map (juxt :resource-name identity))
               (into {}))

          cc
          (closure/make-closure-compiler)

          language-out
          (get-in state [:compiler-options :language-out] :ecmascript3)

          ;; FIXME: are there more options we should take from the user?
          co-opts
          {:pretty-print false
           :source-map true
           ;; FIXME: is there any reason to not always use :simple?
           ;; source maps are pretty good so debugging should not be an issue
           ;; :whitespace is about an order of magnitude faster though
           ;; could use that in :dev but given that npm deps won't change that often
           ;; that might not matter, should cache anyways
           :optimizations :simple
           :language-in :ecmascript-next
           :language-out language-out}

          closure-opts
          (doto (closure/make-options)
            (closure/set-options co-opts))

          _ (do (.addCustomPass closure-opts CustomPassExecutionTime/BEFORE_CHECKS
                  (NodeEnvInlinePass. cc (if (= :release mode)
                                           "production"
                                           "development")))

                (.addCustomPass closure-opts CustomPassExecutionTime/BEFORE_CHECKS
                  (let [m (require-replacement-map state)]
                    (ReplaceRequirePass. cc m))))

          closure-opts
          (doto closure-opts
            (.resetWarningsGuard)
            (.setWarningLevel DiagnosticGroups/NON_STANDARD_JSDOC CheckLevel/OFF)
            (.setNumParallelThreads (get-in state [:compiler-options :closure-threads] 1)))

          result
          (try
            (.compile cc [] source-files closure-opts)
            ;; catch internal closure errors
            (catch Exception e
              (throw (ex-info "failed to convert sources"
                       {:tag ::convert-error
                        :sources (into [] (map :resource-id) sources)}
                       e))))

          _ (closure/throw-errors! state cc result)

          source-map
          (.getSourceMap cc)]

      (-> state
          (closure/log-warnings cc result)
          (util/reduce->
            (fn [state source-node]
              (.reset source-map)

              (let [name
                    (.getSourceFileName source-node)

                    source-file
                    (get source-files-by-name name)

                    {:keys [resource-id output-name] :as rc}
                    (get resource-by-name name)

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

            (->> (ShadowAccess/getJsRoot cc)
                 (.children) ;; the inputs
                 ))))))

#_(defn compile-sources [{:keys [mode] :as state} sources]
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
                  (str "shadow.npm.provide(\"" ns "\", function(module,exports) {\n"
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