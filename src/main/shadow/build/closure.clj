(ns shadow.build.closure
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.env :as env]
            [shadow.cljs.util :as util]
            [shadow.build.output :as output]
            [shadow.build.warnings :as warnings]
            [shadow.build.log :as log]
            [shadow.build.data :as data]
            [shadow.build.npm :as npm]
            [clojure.set :as set]
            [cljs.source-map :as sm]
            [clojure.data.json :as json]
            [shadow.build.cache :as cache]
            [cljs.compiler :as cljs-comp])
  (:import (java.io StringWriter ByteArrayInputStream FileOutputStream File)
           (com.google.javascript.jscomp JSError SourceFile CompilerOptions CustomPassExecutionTime
                                         CommandLineRunner VariableMap SourceMapInput DiagnosticGroups
                                         CheckLevel JSModule CompilerOptions$LanguageMode
                                         SourceMap$LocationMapping BasicErrorManager Result ShadowAccess
                                         SourceMap$DetailLevel SourceMap$Format ClosureCodingConvention CompilationLevel AnonymousFunctionNamingPolicy DiagnosticGroup NodeTraversal)
           (shadow.build.closure ReplaceCLJSConstants NodeEnvInlinePass ReplaceRequirePass PropertyCollector)
           (com.google.javascript.jscomp.deps ModuleLoader$ResolutionMode ModuleNames)
           (com.google.javascript.jscomp.parsing.parser FeatureSet)
           (java.nio.charset Charset)
           (java.util Base64)))

(def SHADOW-TIMESTAMP
  ;; timestamp to ensure that new shadow-cljs release always invalidate caches
  ;; technically needs to check all files but given that they'll all be in the
  ;; same jar one is enough
  (-> (io/resource "shadow/build/closure.clj")
      (.openConnection)
      (.getLastModified)))

(defn noop-error-manager []
  (proxy [BasicErrorManager] []
    (printSummary [])
    (println [level error])))

(defn ^com.google.javascript.jscomp.Compiler make-closure-compiler
  ([]
   (doto (com.google.javascript.jscomp.Compiler.)
     (.disableThreads)))
  ([out-or-error-manager]
   (doto (com.google.javascript.jscomp.Compiler. out-or-error-manager)
     (.disableThreads))))

;;;
;;; partially taken from cljs/closure.clj
;;; removed the things we don't need
;;;
(def check-levels
  {:error CheckLevel/ERROR
   :warning CheckLevel/WARNING
   :off CheckLevel/OFF})

(def warning-types
  {:access-controls DiagnosticGroups/ACCESS_CONTROLS
   :ambiguous-function-decl DiagnosticGroups/AMBIGUOUS_FUNCTION_DECL
   :analyzer-checks DiagnosticGroups/ANALYZER_CHECKS
   :check-eventful-object-disposal DiagnosticGroups/CHECK_EVENTFUL_OBJECT_DISPOSAL
   :check-regexp DiagnosticGroups/CHECK_REGEXP
   :check-types DiagnosticGroups/CHECK_TYPES
   :check-useless-code DiagnosticGroups/CHECK_USELESS_CODE
   :check-variables DiagnosticGroups/CHECK_VARIABLES
   :closure-dep-method-usage-checks DiagnosticGroups/CLOSURE_DEP_METHOD_USAGE_CHECKS
   :conformance-violations DiagnosticGroups/CONFORMANCE_VIOLATIONS
   :const DiagnosticGroups/CONST
   :constant-property DiagnosticGroups/CONSTANT_PROPERTY
   :debugger-statement-present DiagnosticGroups/DEBUGGER_STATEMENT_PRESENT
   :deprecated DiagnosticGroups/DEPRECATED
   :deprecated-annotations DiagnosticGroups/DEPRECATED_ANNOTATIONS
   :duplicate-message DiagnosticGroups/DUPLICATE_MESSAGE
   :duplicate-vars DiagnosticGroups/DUPLICATE_VARS
   :es3 DiagnosticGroups/ES3
   :es5-strict DiagnosticGroups/ES5_STRICT
   :externs-validation DiagnosticGroups/EXTERNS_VALIDATION
   :extra-require DiagnosticGroups/EXTRA_REQUIRE
   :fileoverview-jsdoc DiagnosticGroups/FILEOVERVIEW_JSDOC
   :function-params DiagnosticGroups/FUNCTION_PARAMS
   :global-this DiagnosticGroups/GLOBAL_THIS
   ;; :inferred-const-checks DiagnosticGroups/INFERRED_CONST_CHECKS
   :internet-explorer-checks DiagnosticGroups/INTERNET_EXPLORER_CHECKS
   :invalid-casts DiagnosticGroups/INVALID_CASTS
   :j2cl-checks DiagnosticGroups/J2CL_CHECKS
   :late-provide DiagnosticGroups/LATE_PROVIDE
   :lint-checks DiagnosticGroups/LINT_CHECKS
   :message-descriptions DiagnosticGroups/MESSAGE_DESCRIPTIONS
   :misplaced-type-annotation DiagnosticGroups/MISPLACED_TYPE_ANNOTATION
   :missing-getcssname DiagnosticGroups/MISSING_GETCSSNAME
   :missing-override DiagnosticGroups/MISSING_OVERRIDE
   :missing-polyfill DiagnosticGroups/MISSING_POLYFILL
   :missing-properties DiagnosticGroups/MISSING_PROPERTIES
   :missing-provide DiagnosticGroups/MISSING_PROVIDE
   :missing-require DiagnosticGroups/MISSING_REQUIRE
   :missing-return DiagnosticGroups/MISSING_RETURN
   :non-standard-jsdoc DiagnosticGroups/NON_STANDARD_JSDOC
   :report-unknown-types DiagnosticGroups/REPORT_UNKNOWN_TYPES
   :strict-missing-require DiagnosticGroups/STRICT_MISSING_REQUIRE
   :strict-module-dep-check DiagnosticGroups/STRICT_MODULE_DEP_CHECK
   :strict-requires DiagnosticGroups/STRICT_REQUIRES
   :suspicious-code DiagnosticGroups/SUSPICIOUS_CODE
   :tweaks DiagnosticGroups/TWEAKS
   :type-invalidation DiagnosticGroups/TYPE_INVALIDATION
   :undefined-names DiagnosticGroups/UNDEFINED_NAMES
   :undefined-variables DiagnosticGroups/UNDEFINED_VARIABLES
   :underscore DiagnosticGroups/UNDERSCORE
   :unknown-defines DiagnosticGroups/UNKNOWN_DEFINES
   :unused-local-variable DiagnosticGroups/UNUSED_LOCAL_VARIABLE
   :unused-private-property DiagnosticGroups/UNUSED_PRIVATE_PROPERTY
   :use-of-goog-base DiagnosticGroups/USE_OF_GOOG_BASE
   :violated-module-dep DiagnosticGroups/VIOLATED_MODULE_DEP
   :visiblity DiagnosticGroups/VISIBILITY})

(defn ^CompilerOptions$LanguageMode lang-key->lang-mode [key]
  (case (keyword (str/replace (name key) #"^es" "ecmascript"))
    :no-transpile CompilerOptions$LanguageMode/NO_TRANSPILE ;; same mode as input (for language-out only)
    :ecmascript3 CompilerOptions$LanguageMode/ECMASCRIPT3
    :ecmascript5 CompilerOptions$LanguageMode/ECMASCRIPT5
    :ecmascript5-strict CompilerOptions$LanguageMode/ECMASCRIPT5_STRICT
    :ecmascript6 CompilerOptions$LanguageMode/ECMASCRIPT_2015 ;; (deprecated and remapped)
    :ecmascript6-strict CompilerOptions$LanguageMode/ECMASCRIPT_2015 ;; (deprecated and remapped)
    :ecmascript-2015 CompilerOptions$LanguageMode/ECMASCRIPT_2015
    :ecmascript6-typed CompilerOptions$LanguageMode/ECMASCRIPT6_TYPED
    :ecmascript-2016 CompilerOptions$LanguageMode/ECMASCRIPT_2016
    :ecmascript-2017 CompilerOptions$LanguageMode/ECMASCRIPT_2017
    :ecmascript-next CompilerOptions$LanguageMode/ECMASCRIPT_NEXT))

(defn set-options
  [^CompilerOptions closure-opts opts]

  (when-let [level
             (case (:optimizations opts)
               :advanced CompilationLevel/ADVANCED_OPTIMIZATIONS
               :whitespace CompilationLevel/WHITESPACE_ONLY
               :simple CompilationLevel/SIMPLE_OPTIMIZATIONS
               nil)]
    (.setOptionsForCompilationLevel level closure-opts))

  (when (contains? opts :pretty-print)
    (.setPrettyPrint closure-opts (:pretty-print opts)))

  (when (:source-map opts)
    (doto closure-opts
      (.setSourceMapOutputPath "/dev/null")
      (.setSourceMapDetailLevel SourceMap$DetailLevel/ALL)
      (.setSourceMapFormat SourceMap$Format/V3)))

  (when (contains? opts :pseudo-names)
    (.setGeneratePseudoNames closure-opts (:pseudo-names opts)))

  (when (contains? opts :anon-fn-naming-policy)
    (let [policy (:anon-fn-naming-policy opts)]
      (set! (.anonymousFunctionNaming closure-opts)
        (case policy
          :off AnonymousFunctionNamingPolicy/OFF
          :unmapped AnonymousFunctionNamingPolicy/UNMAPPED
          :mapped AnonymousFunctionNamingPolicy/MAPPED
          (throw (IllegalArgumentException. (str "Invalid :anon-fn-naming-policy value " policy " - only :off, :unmapped, :mapped permitted")))))))

  (when-let [lang-key (:language-in opts)]
    (.setLanguageIn closure-opts (lang-key->lang-mode lang-key)))

  (when-let [lang-key (:language-out opts)]
    (.setLanguageOut closure-opts (lang-key->lang-mode lang-key)))

  (when-let [extra-annotations (:closure-extra-annotations opts)]
    (. closure-opts (setExtraAnnotationNames (map name extra-annotations))))

  (when (contains? opts :print-input-delimiter)
    (set! (.printInputDelimiter closure-opts) (:print-input-delimiter opts)))

  (when (contains? opts :closure-warnings)
    (doseq [[type level] (:closure-warnings opts)]
      (. closure-opts (setWarningLevel (get warning-types type) (get check-levels level)))))

  (when (contains? opts :closure-extra-annotations)
    (. closure-opts (setExtraAnnotationNames (map name (:closure-extra-annotations opts)))))

  (when (contains? opts :closure-module-roots)
    (. closure-opts (setModuleRoots (:closure-module-roots opts))))

  (when (contains? opts :closure-generate-exports)
    (. closure-opts (setGenerateExports (:closure-generate-exports opts))))

  (when (contains? opts :rewrite-polyfills)
    (. closure-opts (setRewritePolyfills (:rewrite-polyfills opts))))

  (. closure-opts (setOutputCharset (Charset/forName (:closure-output-charset opts "UTF-8"))))

  closure-opts)

(defn ^CompilerOptions make-options []
  (doto (CompilerOptions.)
    (.setCodingConvention (ClosureCodingConvention.))))

(def default-externs
  (into [] (CommandLineRunner/getDefaultExterns)))

;; should use the exters/externs-map?
(def known-js-globals
  (->> '[$CLJS
         CLOSURE_BASE_PATH
         COMPILED
         Array
         Boolean
         document
         Date
         DocumentFragment
         encodeURIComponent
         Error
         HTMLElement
         Infinity
         JSON
         Number
         Math
         Object
         ReferenceError
         RegExp
         self
         setTimeout
         String
         Symbol
         TypeError
         console
         eval
         global
         isFinite
         isNaN
         parseFloat
         parseInt
         performance
         postMessage
         process
         require
         WebSocket
         XMLHttpRequest
         window
         __filename
         __dirname]
       (into #{} (map str))))

(defn extern-props-from-js [state]
  (->> (:build-sources state)
       (map #(get-in state [:sources %]))
       (filter #(= :shadow-js (:type %)))
       (map (fn [{:keys [file] :as src}]
              (let [{:keys [properties] :as output}
                    (data/get-output! state src)]

                ;; some files may have been minified by closure
                ;; which follows this naming pattern
                ;; the user is never going to use those directly so we do not
                ;; need to generate externs for these
                (if-not (set/superset? properties #{"a" "b" "c" "d" "e" "f"})
                  properties
                  ;; just strip out the "short" properties
                  (into #{} (remove #(<= (count %) 2)) properties)
                  ))))
       (reduce set/union #{})))

(defn extern-props-from-cljs [state]
  (->> (:build-sources state)
       (map #(get-in state [:sources %]))
       (filter #(= :cljs (:type %)))
       (map (fn [{:keys [ns file] :as src}]
              ;; we know those don't need externs
              (when (not= ns 'cljs.core)
                (let [{:shadow/keys [js-access-properties]}
                      (get-in state [:compiler-env :cljs.analyzer/namespaces ns])]
                  js-access-properties
                  ))))
       (reduce set/union #{})))

(defn extern-globals-from-cljs [state]
  (->> (:build-sources state)
       (map #(get-in state [:sources %]))
       (filter #(= :cljs (:type %)))
       (map (fn [{:keys [ns file] :as src}]
              ;; we know those don't need externs
              (when (not= ns 'cljs.core)
                (let [{:shadow/keys [js-access-global]}
                      (get-in state [:compiler-env :cljs.analyzer/namespaces ns])]
                  js-access-global
                  ))))
       (reduce set/union #{})))

(defn externs-for-build [{:keys [build-id externs-file] :as state}]
  (when (and externs-file (.exists externs-file))
    (let [{props false
           globals true
           :as lines}
          (with-open [rdr (io/reader externs-file)]
            (->> (line-seq rdr)
                 (map str/trim)
                 (filter seq)
                 (remove #(str/starts-with? % "#"))
                 (group-by #(str/starts-with? % "global:"))))]

      [(into #{} props)
       (into #{} (map #(subs % 7)) globals)]
      )))

(defn generate-externs [state]
  (let [[file-props file-globals]
        (externs-for-build state)

        js-externs?
        (and (true? (get-in state [:js-options :generate-externs]))
             (= :shadow (get-in state [:js-options :js-provider])))

        js-props
        (set/union
          (when js-externs?
            (extern-props-from-js state))
          (extern-props-from-cljs state)
          file-props)

        js-globals
        (set/union
          (extern-globals-from-cljs state)
          file-globals)

        content
        (str "/** @constructor */\nfunction ShadowJS() {};\n"
             (->> js-globals
                  (remove known-js-globals)
                  (map cljs-comp/munge)
                  (sort)
                  (map #(str "/** @const {ShadowJS} */ var " % ";"))
                  (str/join "\n"))
             "\n"
             (->> js-props
                  (sort)
                  (map cljs-comp/munge)
                  (map #(str "ShadowJS.prototype." % ";"))
                  (str/join "\n")))]

    ;; not actually required but makes it easier to verify
    (let [file (data/cache-file state "externs.shadow.js")]
      (spit file content))

    (SourceFile/fromCode "externs.shadow.js" content)
    ))

(defn load-externs [{:keys [deps-externs] :as state} generate?]
  (let [externs
        (distinct
          (concat
            (:externs state)
            (get-in state [:compiler-options :externs])
            ;; needed to get rid of process/global errors in cljs/core.cljs
            ["shadow/cljs/externs/node.js"
             ;; always need those
             "shadow/cljs/externs/process.js"]
            (when (= :js (get-in state [:build-options :module-format]))
              ["shadow/cljs/externs/npm.js"])))

        manual-externs
        (when (seq externs)
          (->> externs
               (map
                 (fn [ext]
                   (if-let [rc (or (io/resource ext)
                                   (let [file (io/file ext)]
                                     (when (.exists file)
                                       file)))]
                     (SourceFile/fromCode (str "EXTERNS:" ext) (slurp rc))
                     (do (util/log state {:type :missing-externs :extern ext})
                         nil))))
               (remove nil?)
               ;; just to force the logging
               (into [])))

        deps-externs
        (->> (for [[deps-path externs] deps-externs
                   {:keys [resource-name url] :as ext} externs]
               ;; FIXME: use url? deps-path is accurate enough for now
               (SourceFile/fromCode (str "EXTERNS:" deps-path "!/" resource-name) (slurp url)))
             (into []))]

    (-> []
        (into default-externs)
        (into deps-externs)
        (into manual-externs)
        (cond->
          generate?
          (conj (generate-externs state))))))

(defn register-cljs-protocol-properties
  "this is needed to make :check-types work

   It registers all known CLJS protocols with the Closure TypeRegistry
   each method is as a property on Object since most of the time Closure doesn't know the proper type
   and annotating everything seems unlikely."
  [{::keys [compiler compiler-options]
    :keys [compiler-env build-sources]
    :as state}]

  (when (contains? #{:warning :error} (get-in state [:compiler-options :closure-warnings :check-types]))
    (let [type-reg
          (.getTypeRegistry compiler)

          obj-type
          (.getType type-reg "Object")]

      ;; some general properties
      (doseq [prop
              ["cljs$lang$protocol_mask$partition$"
               "cljs$lang$macro"
               "cljs$lang$test"]]
        (.registerPropertyOnType type-reg prop obj-type))

      ;; find all protocols and register the protocol method properties
      (doseq [ana-ns (vals (::ana/namespaces compiler-env))
              :let [{:keys [defs]} ana-ns]
              def (vals defs)
              :when (-> def :meta :protocol-symbol)]

        (let [{:keys [name meta]} def
              {:keys [protocol-info]} meta

              prefix
              (str (comp/protocol-prefix name))]

          ;; the marker prop
          ;; some.prototype.cljs$core$ISeq$ = cljs.core.PROTOCOL_SENTINEL;
          (.registerPropertyOnType type-reg prefix obj-type)

          (doseq [[meth-name meth-sigs] (:methods protocol-info)
                  :let [munged-name (comp/munge meth-name)]
                  meth-sig meth-sigs]

            (let [arity
                  (count meth-sig)

                  prop
                  (str prefix munged-name "$arity$" arity)]

              ;; register each arity some.prototype.cljs$core$ISeq$_seq$arity$1
              (.registerPropertyOnType type-reg prop obj-type)
              ))))))
  state)

(defn read-variable-map [state name]
  (let [map-file (data/cache-file state name)]
    (when (.exists map-file)
      (try
        (VariableMap/load (.getAbsolutePath map-file))
        (catch Exception e
          (util/log state {:type ::map-load-error :file map-file :ex e})
          nil)))))

(defn use-variable-maps? [state]
  (and (not (true? (get-in state [:compiler-options :pseudo-names])))
       (= :advanced (get-in state [:compiler-options :optimizations]))))

(defn read-variable-maps
  [{::keys [compiler compiler-options] :as state}]

  (when (use-variable-maps? state)

    (when-some [data (read-variable-map state "closure.property.map")]
      (.setInputPropertyMap compiler-options data))

    (when-some [data (read-variable-map state "closure.variable.map")]
      (.setInputVariableMap compiler-options data)))

  state)

(defn write-variable-map [state name map]
  (when map
    (let [map-file
          (doto (data/cache-file state name)
            (io/make-parents))

          bytes
          (ByteArrayInputStream. (.toBytes map))]

      (with-open [out (FileOutputStream. map-file)]
        (io/copy bytes out)))))

(defn write-variable-maps [{::keys [result] :as state}]
  (when (use-variable-maps? state)
    (write-variable-map state "closure.variable.map" (.-variableMap result))
    (write-variable-map state "closure.property.map" (.-propertyMap result)))
  state)

(defmethod log/event->str ::warnings
  [{:keys [warnings]}]
  (warnings/print-warnings warnings))

(defn js-error-xf [state ^com.google.javascript.jscomp.Compiler cc]
  (map (fn [^JSError err]
         (let [source-name
               (.-sourceName err)

               line
               (.getLineNumber err)

               column
               (.getCharno err)

               mapping
               (.getSourceMapping cc source-name line column)

               src
               (when mapping
                 (let [src-name (.getOriginalFile mapping)]
                   (try
                     (data/get-source-by-name state src-name)
                     (catch Exception e
                       ;; we might be importing JS files with source maps
                       ;; which map back to source files we do not have
                       nil))))]

           (if-not src
             ;; FIXME: add source-excerpt if src wasn't CLJS
             {:resource-name source-name
              :source-name source-name
              :line line
              :column column
              :msg (.-description err)}

             (let [{:keys [resource-id resource-name file url]} src

                   file
                   (or (when file (.getAbsolutePath file))
                       (let [x (str url)]
                         (if-let [idx (str/index-of x ".m2")]
                           (str "~/" (subs x idx))
                           x))
                       (.getOriginalFile mapping))

                   line
                   (.getLineNumber mapping)

                   column
                   (.getColumnPosition mapping)

                   source-excerpt
                   (warnings/get-source-excerpt state src {:line line :column column})]

               {:resource-id resource-id
                :resource-name resource-name
                :source-name source-name
                :file file
                :line line
                :column column
                :msg (.-description err)
                :source-excerpt source-excerpt}
               ))))))

(defn closure-source-file [name code]
  (SourceFile/fromCode name code))

(defn add-input-source-maps [{:keys [build-sources] :as state} cc]
  (doseq [src-id build-sources
          :let [{:keys [resource-name type output-name] :as rc}
                (data/get-source-by-id state src-id)]
          :when (not= :shadow-js type)]

    (let [{:keys [source-map-json] :as output}
          (data/get-output! state rc)

          source-map-json
          (or source-map-json
              ;; source-map is CLJS source map data, need to encode it to json so closure can read it
              (output/encode-source-map rc output))]

      ;; not using SourceFile/fromFile as the name that gets displayed in warnings sucks
      ;; public/js/cljs-runtime/cljs/core.cljs vs cljs/core.cljs
      (->> (closure-source-file output-name source-map-json)
           (SourceMapInput.)
           (.addInputSourceMap cc resource-name))
      )))

;; CLOSURE-WARNING: Property nodeGlobalRequire never defined on goog
;; Original: ~/.m2/repository/org/clojure/clojurescript/1.9.542/clojurescript-1.9.542.jar!/cljs/core.cljs [293:4]
;; cljs/core.cljs contains a call to goog.nodeGlobalRequire(...) which is only used in self-host mode
;; but causes the closure compiler --check to complain
;; since we are never going to use it just emit a noop to get rid of the warning

(def goog-nodeGlobalRequire-fix
  "\ngoog.nodeGlobalRequire = function(path) { return false };\n")

(defn make-js-modules
  [{:keys [build-modules closure-configurators compiler-options build-sources] :as state}]

  ;; modules that only contain foreign sources must not be exposed to closure
  ;; since they technically do not depend on goog but closure only allows one root module
  ;; when optimizing
  (let [skip-mods
        (->> build-modules
             (filter :all-foreign)
             (map :module-id)
             (into #{}))

        js-provider
        (get-in state [:js-options :js-provider])

        required-js-names
        (data/js-names-accessed-from-cljs state build-sources)

        js-mods
        (reduce
          (fn [js-mods {:keys [module-id output-name depends-on sources] :as mod}]
            (let [js-mod (JSModule. output-name)]

              (doseq [other-mod-id depends-on
                      :when (not (contains? skip-mods other-mod-id))
                      :let [other-mod (get js-mods other-mod-id)]]
                (when-not other-mod
                  (throw (ex-info (format "module \"%s\" depends on undefined module \"%s\", defined modules are %s"
                                    module-id
                                    other-mod-id
                                    (set (map :module-id build-modules)))

                           {:id module-id :other other-mod-id})))

                (.addDependency js-mod other-mod))

              (doseq [{:keys [resource-id resource-name output-name ns type output] :as rc}
                      (->> sources
                           (map #(data/get-source-by-id state %))
                           (remove util/foreign?))]
                (let [{:keys [js source] :as output}
                      (data/get-output! state rc)

                      js
                      (cond
                        (= "goog/base.js" resource-name)
                        (str (output/closure-defines state)
                             js
                             goog-nodeGlobalRequire-fix)

                        (= :shadow-js type)
                        (str (when (contains? required-js-names ns)
                               (str "goog.provide(\"" ns "\");\n"
                                    ns " = " (npm/shadow-js-require rc))))

                        :else
                        js)]

                  (.add js-mod (closure-source-file resource-name js))))

              (assoc js-mods module-id js-mod)))
          {}
          (->> build-modules
               (remove :all-foreign)))


        modules
        (->> (for [{:keys [module-id] :as mod} build-modules]
               (assoc mod :js-module (get js-mods module-id)))
             (into []))]

    (assoc state ::modules modules)))

(defn as-module-exports [defs]
  (when (seq defs)
    (str "\nmodule.exports={" defs "};")))

(defn make-js-module-per-source
  [{:keys [compiler-env build-sources] :as state}]

  (let [js-mods
        (reduce
          (fn [js-mods resource-id]
            (let [{:keys [ns deps output-name] :as src}
                  (data/get-source-by-id state resource-id)

                  {:keys [js] :as output}
                  (data/get-output! state {:resource-id resource-id})

                  defs
                  (cond
                    ;; special case for js shim namespaces
                    (:export-self src)
                    (str "\nmodule.exports=" ns ";")

                    ;; for every CLJS namespace, collect every ^:export def
                    ;; and copy them to module.exports, the goog.export... will be removed
                    ;; as that exports to global which is not what we want
                    ns
                    (->> (get-in compiler-env [::ana/namespaces ns :defs])
                         (vals)
                         (filter #(get-in % [:meta :export]))
                         (map :name)
                         (map (fn [def]
                                (let [export-name
                                      (-> def name str comp/munge pr-str)]
                                  (str export-name ":" (comp/munge def)))))
                         (str/join ",")
                         (as-module-exports))

                    :else
                    nil)

                  base?
                  (= output/goog-base-id resource-id)

                  code
                  (str (when base?
                         (output/closure-defines state))
                       js
                       (when base?
                         (str goog-nodeGlobalRequire-fix
                              "\ngoog.global = global;"))
                       ;; FIXME: module.exports will become window.module.exports, rewritten later
                       (when (seq defs)
                         defs))

                  js-mod
                  (doto (JSModule. (util/flat-filename output-name))
                    (.add (closure-source-file output-name code)))]

              (doseq [dep
                      (->> (data/deps->syms state src)
                           (map #(data/get-source-id-by-provide state %))
                           (distinct)
                           (into []))]

                (let [other-mod (get js-mods dep)]
                  (when-not other-mod
                    (throw (ex-info (format "internal module error, no mod for dep:%s" dep)
                             {:dep dep})))
                  (.addDependency js-mod other-mod)))

              (assoc js-mods resource-id js-mod)))
          {}
          build-sources)

        modules
        (->> build-sources
             (map (fn [resource-id]
                    (let [src (data/get-source-by-id state resource-id)]
                      {:module-id resource-id
                       :name (:name src)
                       :output-name (util/flat-filename (:output-name src))
                       :js-module (get js-mods resource-id)
                       :sources [resource-id]})))
             (into []))]

    (assoc state ::modules modules)))

(defn setup
  [{::keys [modules]
    :keys [compiler-options] :as state}]
  (let [source-map?
        (boolean (:source-map compiler-options))

        cc
        (make-closure-compiler (noop-error-manager))

        closure-opts
        (doto (make-options)
          (.resetWarningsGuard)
          (.setNumParallelThreads (get-in state [:compiler-options :closure-threads] 1))

          (.setWarningLevel DiagnosticGroups/CHECK_TYPES CheckLevel/OFF)
          ;; really only want the undefined variables warnings
          ;; must turn off CHECK_VARIABLES first or it will complain too much (REDECLARED_VARIABLES)
          (.setWarningLevel DiagnosticGroups/CHECK_VARIABLES CheckLevel/OFF)
          ;; this one is very helpful to spot missing externs
          ;; (js/React...) will otherwise just work without externs
          (.setWarningLevel DiagnosticGroups/UNDEFINED_VARIABLES CheckLevel/WARNING))]

    (set-options closure-opts compiler-options)

    (when source-map?
      ;; FIXME: path is not used at all but needs to be set
      ;; otherwise the applyInputSourceMaps will have no effect since it happens
      ;; inside a if (sourceMapOutputPath != null)

      (.setSourceMapOutputPath closure-opts "/dev/null")
      (.setSourceMapIncludeSourcesContent closure-opts true)
      (.setApplyInputSourceMaps closure-opts true)

      (add-input-source-maps state cc))

    (.addCustomPass closure-opts CustomPassExecutionTime/BEFORE_CHECKS (ReplaceCLJSConstants. cc))
    (.addCustomPass closure-opts CustomPassExecutionTime/BEFORE_CHECKS
      (NodeEnvInlinePass. cc (if (= :release (:mode state))
                               "production"
                               "development")))

    ;; (fn [closure-compiler compiler-options state])
    (doseq [cfg (:closure-configurators compiler-options)]
      (cfg cc closure-opts state))

    (when (= :js (get-in state [:build-options :module-format]))

      ;; cut the goog.exportSymbol call CLJS may have generated
      ;; since they will still export to window which is not what we want
      (set! (.-stripTypePrefixes closure-opts) #{"goog.exportSymbol"})
      ;; can be anything but will be repeated a lot
      ;; $ vs $CLJS adds about 4kb to cljs.core alone but that is only 250 bytes after gzip
      ;; choosing $CLJS so the alias is the same in dev mode and avoiding potential conflicts
      ;; with jQuery or so when using only $
      (.setRenamePrefixNamespace closure-opts "$CLJS"))

    (assoc state
      ::externs (load-externs state true)
      ::compiler cc
      ::compiler-options closure-opts)))

(defn load-extern-properties [{::keys [extern-properties] :as state}]
  (if extern-properties
    state
    (let [cc (make-closure-compiler)
          co (make-options)

          externs
          (load-externs state false)

          result
          (.compile cc externs [] co)

          ;; already immutable set, but incompatible
          ;; com.google.common.collect.RegularImmutableSet cannot be cast to clojure.lang.IPersistentCollection
          extern-properties
          (into #{} (ShadowAccess/getExternProperties cc))]

      (when-not (.success result)
        (throw (ex-info "externs trouble" {})))

      (assoc state ::extern-properties extern-properties)
      )))

(defn rewrite-node-global-access [js]
  (-> js
      ;; RescopeGlobalSymbols rewrites
      ;; require("react")
      ;; to
      ;; (0,window.require)("react") // FIXME: why the 0,?
      ;; webpack & co no not recognize this
      ;; so we must turn it back into
      ;; /*********/require("react")
      ;; with the padding to not mess up source maps
      (str/replace "(0,window.require)(" "/*********/require(")
      ;; window.module.exports
      ;; /*****/module.exports
      (str/replace "window.module.exports", "/*****/module.exports")
      (str/replace "window.__filename", "/*****/__filename")
      (str/replace "window.__dirname", "/*****/__dirname")
      ))

(defn dump-js-modules [modules]
  (doseq [js-mod modules]
    (println
      (str (format "--module %s:%d"
             (.getName js-mod)
             (count (.getInputs js-mod)))
           (let [deps (.getDependencies js-mod)]
             (when (seq deps)
               (format ":%s" (->> deps
                                  (map #(.getName %))
                                  (str/join ",")))))
           " \\"
           ))

    (doseq [input (.getInputs js-mod)]
      (println (format "--js %s \\" (.getName input))))))

(defn source-map-sources [state]
  (->> (:build-sources state)
       (map (fn [src-id]
              (let [{:keys [resource-name] :as rc}
                    (get-in state [:sources src-id])

                    {:keys [source]}
                    (data/get-output! state rc)]

                (closure-source-file resource-name (or source "")))))
       (into [])))

(defn add-sources-to-source-map [state source-map]
  ;; this needs to add ALL sources, not just the sources of the module
  ;; this is because cross-module motion may have moved code
  ;; closure will only include the relevant files but it needs to be able to find all
  ;; for some reason .reset removes them all so we need to repeat this for every module
  ;; since modules require .reset
  (doseq [src-file (source-map-sources state)]
    (.addSourceFile source-map src-file)))

(defn compile-js-modules
  [{::keys [externs modules compiler compiler-options] :as state}]
  (let [js-mods
        (->> modules
             (map :js-module)
             (remove nil?)
             (into []))

        ^Result result
        (.compileModules
          compiler
          externs
          js-mods
          compiler-options)

        success?
        (.success result)

        js-provider
        (get-in state [:js-options :js-provider])

        source-map
        (when (and success? (get-in state [:compiler-options :source-map]))
          (.getSourceMap compiler))]

    (-> state
        (assoc ::result result)
        (cond->
          success?
          (update ::modules
            (fn [modules]
              (->> modules
                   (map (fn [{:keys [goog-base module-id prepend append output-name js-module sources includes] :as mod}]
                          ;; must reset source map before calling .toSource
                          (when source-map
                            (.reset source-map)
                            (add-sources-to-source-map state source-map))

                          (let [js
                                (if-not js-module ;; foreign only doesnt have JSModule instance
                                  ""
                                  (.toSource compiler js-module))]

                            (if (and (not (seq js))
                                     (not (seq prepend))
                                     (not (seq append)))
                              (assoc mod :dead true :output "")

                              (-> mod
                                  (assoc :output js)
                                  (cond->
                                    (not= :goog (get-in state [:build-options :module-format]))
                                    (update :output rewrite-node-global-access)

                                    (and js-module source-map)
                                    (merge (let [sw (StringWriter.)]
                                             (.appendTo source-map sw output-name)
                                             {:source-map-json (.toString sw)}))
                                    ))))))
                   (into []))))))))

(defn strip-dead-modules
  "remove any modules that were completely eliminated or moved by closure

   must be called after compile-js-modules
   this would leave empty files otherwise that nobody needs"
  [{::keys [modules] :as state}]
  ;; all code of a module may have been DCE or moved
  (let [dead-modules
        (->> modules
             (filter :dead)
             (map :output-name)
             (into #{}))

        ;; a module may have contained multiple sources which were all removed
        dead-sources
        (->> modules
             (remove #(contains? dead-modules (:output-name %)))
             (mapcat :sources)
             (into #{}))]

    (assoc state
      ::dead-modules dead-modules
      ::dead-sources dead-sources)))

(defn log-warnings
  ([{::keys [compiler result] :as state}]
   (log-warnings state compiler result))
  ([state compiler result]
   (let [warnings (into [] (js-error-xf state compiler) (.warnings result))]
     (when (seq warnings)
       (util/log state {:type ::warnings
                        :warnings warnings})))

   state))

(defn throw-errors!
  ([{::keys [compiler result] :as state}]
   (throw-errors! state compiler result))
  ([state compiler result]
   (when-not (.success result)
     (let [errors (into [] (js-error-xf state compiler) (.errors result))]
       (throw (ex-info "closure errors" {:tag ::errors :errors errors}))))

   state))

(defn get-js-module-requires [{::keys [dead-modules] :as state} js-module]
  ;; can't use .getAllDependencies since that is a set and unsorted
  (->> (.getDependencies js-module)
       (mapcat (fn [dep-mod]
                 ;; if a module is dead we still need the dependencies it would have brought in
                 ;; all code may have been moved out of one file
                 (if-not (contains? dead-modules (.getName dep-mod))
                   [dep-mod]
                   (get-js-module-requires state dep-mod)
                   )))
       (into [])))

(defn module-wrap-npm
  "adds npm specific prepend/append but does not modify output

   must be called after strip-dead-modules
   can't do this before compiling since we need to know which modules where removed"
  ;; FIXME: could do this in compile-modules
  [{::keys [modules dead-modules dead-sources] :as state}]
  (let [env-prepend
        "var window=global;var $CLJS=require(\"./cljs_env\");"]

    (update state ::modules
      (fn [modules]
        (->> modules
             (map (fn [{:keys [name js-module] :as mod}]
                    (let [requires
                          (->> (get-js-module-requires state js-module)
                               (map #(.getName %))
                               (distinct)
                               (map #(str "require(\"./" % "\");"))
                               (str/join ""))]

                      (-> mod
                          ;; the npm prepend must be one line, will mess up source lines otherwise
                          (update :prepend #(str env-prepend requires #_"$.module=module;\n" "\n" %))
                          ;; the set this to null so it doesn't leak to other modules
                          ;; (update :append str "$.module=null;")
                          ))))
             (into []))))))

(defn- set-check-only
  [{::keys [compiler-options] :as state}]
  (.setChecksOnly compiler-options true)
  state)

(defn check
  [state]
  (util/with-logged-time
    [state {:type :closure-check}]
    (-> state
        (make-js-modules)
        (setup)
        (register-cljs-protocol-properties)
        (set-check-only)
        (compile-js-modules)
        (log-warnings)
        (throw-errors!))))

(defn should-expand-modules? [{:keys [build-modules] :as state}]
  (and (= 1 (count build-modules))
       (:expand (first build-modules))))

(defn optimize
  "takes the current defined modules and runs it through the closure compiler"
  [{:keys [build-options build-modules] :as state}]
  (when-not (seq build-modules)
    (throw (ex-info "optimize before compile?" {})))

  (when (= :none (get-in state [:compiler-options :optimizations] :none))
    (throw (ex-info "optimizations set to :none, can't optimize" {})))

  (util/with-logged-time
    [state {:type :closure-optimize}]

    (let [expand-modules?
          (should-expand-modules? state)]

      (-> state
          (cond->
            (not expand-modules?)
            (make-js-modules)
            expand-modules?
            (make-js-module-per-source))
          (setup)
          (read-variable-maps)
          (compile-js-modules)
          (log-warnings)
          (throw-errors!)
          (cond->
            (= :js (get-in state [:build-options :module-format]))
            (-> (strip-dead-modules)
                (module-wrap-npm)))
          (write-variable-maps)))))

(def polyfill-name
  "SHADOW$POLYFILL.js")

(defmethod log/event->str ::convert
  [{:keys [num-sources] :as event}]
  (format "Converting %d JS sources" num-sources))

(defn replace-file-references
  "extremely hacky way to escape the mess that is NODE ModuleResolver
   replacing all require/imports with absolute file paths so we can use BROWSER

   this lets us use our resolve behavior and custom :resolve configs, no need to feed
   package.json files to the compiler.

   other option would be to file a proper PR to turn ModuleLoader into an interface
   so we can control the lookup behavior.

   could also do this as a compiler pass but there is no way to run a compiler pass
   BEFORE the module stuff is resolved.

   doing this via string replacement since parsing the ast and emitting JS would
   require source maps twice, ie. way more work. JSInspector already recorded all the
   locations we need."
  [state {:keys [resource-name ns js-requires js-imports js-str-offsets] :as rc} source]
  (let [aliases
        (get-in state [:str->sym ns])]

    (reduce
      (fn [source {:keys [string offset] :as x}]
        (let [alias (get aliases string)]
          (if-not alias ;; goog:cljs.core is not aliased
            source
            (let [other-rc
                  (data/get-source-by-provide state alias)

                  abs-path
                  (str "/" (:resource-name other-rc))]

              (str (subs source 0 (inc offset))
                   abs-path
                   (subs source (+ 1 offset (count string))))
              ))))
      source
      ;; must start at the end since we are inserting longer strings
      (reverse js-str-offsets))))

(defn convert-sources
  "takes a list of :js sources and rewrites them to closure JS"
  [{:keys [project-dir npm] :as state} sources]

  (let [source-files
        (for [{:keys [resource-name file] :as src} sources]
          (let [source
                (data/get-source-code state src)
                source
                (replace-file-references state src source)]
            (closure-source-file resource-name source)))

        required-shadow-js
        (->> sources
             (mapcat #(data/deps->syms state %))
             (into #{}))

        shadow-js-files
        (->> required-shadow-js
             (map #(data/get-source-by-provide state %))
             (filter #(= :shadow-js (:type %)))
             (map (fn [{:keys [resource-name ns] :as rc}]
                    (closure-source-file resource-name (npm/shadow-js-require rc))))
             (into []))

        source-file-names
        (into #{} (map #(.getName %)) source-files)

        source-files
        ;; closure prepends polyfills to the first resource
        ;; adding an empty placeholder ensures we can take them out easily
        (-> [(closure-source-file polyfill-name "")]
            ;; then add all resources
            (into shadow-js-files)
            (into source-files))

        ;; this includes all files (sources + package.json files)
        source-files-by-name
        (->> source-files
             (map (juxt #(.getName %) identity))
             (into {}))

        #_(->> source-files-by-name
               (keys)
               (pprint))

        ;; this only includes resources we actually want output for
        resource-by-name
        (->> sources
             (map (juxt :resource-name identity))
             (into {}))

        cc
        (make-closure-compiler)

        language-out
        (get-in state [:compiler-options :language-out] :ecmascript3)

        rewrite-polyfills
        (get-in state [:compiler-options :rewrite-polyfills])

        ;; FIXME: are there more options we should take from the user?
        co-opts
        {:pretty-print true
         :source-map true
         :language-in :ecmascript-next
         :language-out language-out}

        co
        (doto (make-options)
          (set-options co-opts))

        ;; since we are not optimizing closure will not inject polyfills
        ;; so we manually inject all
        ;; FIXME: figure out if we can determine which are actually needed
        _ (when rewrite-polyfills
            (doto co
              (.setRewritePolyfills true)
              (.setPreventLibraryInjection false)
              (.setForceLibraryInjection ["es6_runtime"])))

        co
        (doto co
          ;; we only transpile, no optimizing or type checking
          (.setSkipNonTranspilationPasses true)

          (.setWarningLevel DiagnosticGroups/NON_STANDARD_JSDOC CheckLevel/OFF)

          (.setProcessCommonJSModules true)
          (.setTransformAMDToCJSModules true)
          ;; just in case there are some type annotations
          (.setPreserveTypeAnnotations true)

          (.setNumParallelThreads (get-in state [:compiler-options :closure-threads] 1))
          (.setModuleResolutionMode ModuleLoader$ResolutionMode/BROWSER))

        result
        (try
          (util/with-logged-time [state {:type ::convert
                                         :num-sources (count source-files)}]
            (.compile cc [] source-files co))
          ;; catch internal closure errors
          (catch Exception e
            (throw (ex-info "failed to convert sources"
                     {:tag ::convert-error
                      :sources (into [] (map :resource-id) sources)}
                     e))))

        _ (throw-errors! state cc result)

        source-map
        (.getSourceMap cc)]

    (-> state
        (log-warnings cc result)
        (util/reduce->
          (fn [state source-node]
            (.reset source-map)

            (let [name
                  (.getSourceFileName source-node)

                  source-file
                  (get source-files-by-name name)

                  {:keys [resource-id output-name] :as rc}
                  (get resource-by-name name)]

              (cond
                (= polyfill-name name)
                (let [js (ShadowAccess/nodeToJs cc source-map source-node)]
                  ;; these are for development only
                  (assoc state :polyfill-js js))

                (nil? rc)
                state

                :else
                (let [js
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
                       :source (.getCode source-file)
                       :source-map-json sm-json}]

                  (assoc-in state [:output resource-id] output)))))

          (->> (ShadowAccess/getJsRoot cc)
               (.children) ;; the inputs
               )))))

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

(defn convert-sources-simple*
  "takes a list of :npm sources and rewrites in a browser compatible way, no full conversion"
  [{:keys [project-dir js-options npm mode build-sources] :as state} sources]
  (let [source-files
        (->> (for [{:keys [resource-id resource-name ns file deps] :as src} sources]
               (let [source (data/get-source-code state src)]
                 (closure-source-file resource-name
                   (str "shadow$provide[\"" ns "\"] = function(global,require,module,exports) {\n"
                        (if (str/ends-with? resource-name ".json")
                          (str "module.exports=(" source ");")
                          source)
                        "\n};"))))
             #_(map #(do (println (.getCode %)) %))
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
        (make-closure-compiler)

        language-out
        (get-in state [:compiler-options :language-out] :ecmascript3)

        ;; FIXME: are there more options we should take from the user?
        co-opts
        {:source-map (:source-maps js-options true)
         ;; FIXME: is there any reason to not always use :simple?
         ;; source maps are pretty good so debugging should not be an issue
         ;; :whitespace is about an order of magnitude faster though
         ;; could use that in :dev but given that npm deps won't change that often
         ;; that might not matter, should cache anyways
         :optimizations (:optimizations js-options :simple) ;; FIXME: validate whitespace or simple
         :pretty-print (:pretty-print js-options false)
         :language-in :ecmascript5 ;; es6+ is transformed by babel first
         :language-out language-out}

        property-collector
        (PropertyCollector. cc)

        closure-opts
        (doto (make-options)
          (set-options co-opts)
          (.resetWarningsGuard)

          (.setStrictModeInput false)

          (.addCustomPass CustomPassExecutionTime/BEFORE_CHECKS
            (NodeEnvInlinePass. cc (if (= :release mode)
                                     "production"
                                     "development")))

          (.addCustomPass CustomPassExecutionTime/BEFORE_CHECKS
            (let [m (require-replacement-map state)]
              (ReplaceRequirePass. cc m)))

          (.addCustomPass CustomPassExecutionTime/AFTER_OPTIMIZATION_LOOP property-collector)

          (.setWarningLevel DiagnosticGroups/NON_STANDARD_JSDOC CheckLevel/OFF)
          (.setWarningLevel DiagnosticGroups/MISPLACED_TYPE_ANNOTATION CheckLevel/OFF)
          ;; node_modules/@firebase/util/dist/cjs/src/constants.ts:26: ERROR - @define variable  assignment must be global
          (.setWarningLevel
            (DiagnosticGroup.
              (into-array [ShadowAccess/NON_GLOBAL_DEFINE_INIT_ERROR]))
            CheckLevel/OFF)
          ;; unreachable code in react
          (.setWarningLevel DiagnosticGroups/CHECK_USELESS_CODE CheckLevel/OFF)
          (.setNumParallelThreads (get-in state [:compiler-options :closure-threads] 1)))

        result
        (try
          (util/with-logged-time [state {:type ::convert
                                         :num-sources (count source-files)}]
            (.compile cc [] source-files closure-opts))
          ;; catch internal closure errors
          (catch Exception e
            (throw (ex-info "failed to convert sources"
                     {:tag ::convert-error
                      :sources (into [] (map :resource-id) sources)}
                     e))))

        _ (throw-errors! state cc result)

        source-map
        (.getSourceMap cc)]

    (-> state
        (log-warnings cc result)
        (util/reduce->
          (fn [state source-node]
            (.reset source-map)

            (let [name
                  (.getSourceFileName source-node)

                  source-file
                  (get source-files-by-name name)

                  {:keys [resource-id ns output-name deps] :as rc}
                  (get resource-by-name name)

                  js
                  (try
                    (ShadowAccess/nodeToJs cc source-map source-node)
                    (catch Exception e
                      (throw (ex-info (format "failed to generate JS for \"%s\"" name) {:name name} e))))

                  ;; the :simple optimization may remove conditional requires
                  ;; the JS inspector is not smart enough to detect this without optimizing itself
                  ;; so instead just check if the compiled JS still contains the module name
                  ;; the name is unique enough so it shouldn't run into something the user actually typed
                  ;; module$node_modules$react$cjs$react_production_min
                  ;; it is not done as a compiler pass since I cannot figure out how to do it
                  ;; the require("thing") is renamed to b("thing") so I can't check NodeUtil.isCallTo("require")
                  ;; no idea if I can get the original name after renaming, its not always b so can't use that
                  ;; anyways this should be good enough and fixes the react conditional require issue
                  removed-requires
                  (->> (get-in state [:str->sym ns])
                       (vals)
                       ;; test at least for ("module$thing")
                       ;; so it doesn't conflict with module$thing$b
                       (remove #(str/includes? js (str "(\"" % "\")")))
                       (into #{}))

                  sw
                  (StringWriter.)

                  ;; for sourcesContent
                  _ (.addSourceFile source-map source-file)
                  _ (.appendTo source-map sw output-name)

                  sm-json
                  (.toString sw)

                  deps
                  (data/deps->syms state rc)

                  actual-requires
                  (into #{} (remove removed-requires) deps)

                  output
                  {:resource-id resource-id
                   :js js
                   :source (.getCode source-file)
                   :removed-requires removed-requires
                   :actual-requires actual-requires
                   :properties (into #{} (-> property-collector (.-properties) (.get name)))
                   :compiled-at (System/currentTimeMillis)
                   :source-map-json sm-json}]

              (assoc-in state [:output resource-id] output)
              ))

          (->> (ShadowAccess/getJsRoot cc)
               (.children) ;; the inputs
               )))))

(defmethod log/event->str ::cache-read
  [{:keys [num-files]}]
  (format "JS Cache read: %d JS files" num-files))

(defmethod log/event->str ::cache-write
  [{:keys [num-files]}]
  (format "JS Cache write: %d JS files" num-files))

(defn convert-sources-simple
  "convert and caches"
  [state sources]
  (let [cache-index-file
        (data/cache-file state "shadow-js" "index.json.transit")

        cache-index
        (if (.exists cache-index-file)
          (cache/read-cache cache-index-file)
          ;; ensure that at least the directoy exists so we can write files into it later
          (do (io/make-parents cache-index-file)
              {}))

        cache-files
        (->> (for [{:keys [resource-id cache-key] :as src} sources
                   ;; FIXME: this should probably check if the file exists
                   ;; never should delete individual cached files without also removing the index though
                   :when (and (= SHADOW-TIMESTAMP (:SHADOW-TIMESTAMP cache-index))
                              (= cache-key (get cache-index resource-id)))]
               src)
             ;; need to preserve order for later
             (into []))

        cache-files-set
        (into #{} (map :resource-id) cache-files)

        recompile-sources
        (->> sources
             (remove #(contains? cache-files-set (:resource-id %)))
             (into []))

        need-compile?
        (boolean (seq recompile-sources))

        state
        (if-not need-compile?
          state
          (convert-sources-simple* state recompile-sources))

        cache-index-updated
        (if-not need-compile?
          cache-index
          (util/with-logged-time [state {:type ::cache-write
                                         :num-files (count recompile-sources)}]
            (reduce
              (fn [idx {:keys [cache-key resource-id output-name] :as compiled-src}]
                (let [output
                      (data/get-output! state compiled-src)

                      cache-file
                      (data/cache-file state "shadow-js" output-name)]

                  (cache/write-file cache-file output)

                  (assoc idx resource-id cache-key)))
              (assoc cache-index :SHADOW-TIMESTAMP SHADOW-TIMESTAMP)
              recompile-sources)))]

    (when need-compile?
      (cache/write-file cache-index-file cache-index-updated))

    (let [state
          (if-not (seq cache-files)
            state
            (util/with-logged-time [state {:type ::cache-read
                                           :num-files (count cache-files)}]
              (reduce
                (fn [state {:keys [resource-id output-name] :as cached-rc}]
                  (if (get-in state [:output resource-id])
                    state
                    (let [cache-file
                          (data/cache-file state "shadow-js" output-name)

                          {:keys [actual-requires] :as cached-output}
                          (-> (cache/read-cache cache-file)
                              (assoc :cached true))]

                      (assoc-in state [:output resource-id] cached-output)
                      )))
                state
                cache-files)))

          required-js-names
          (data/js-names-accessed-from-cljs state)

          {:keys [live-js-deps dead-js-deps required js-properties]}
          (->> sources
               (reverse)
               (reduce
                 (fn [{:keys [required] :as idx} {:keys [ns provides] :as src}]
                   (if-not (seq (set/intersection required provides))
                     (update idx :dead-js-deps conj ns)
                     (let [{:keys [actual-requires properties]}
                           (data/get-output! state src)]
                       (-> idx
                           (update :js-properties set/union properties)
                           (update :live-js-deps conj ns)
                           (update :required set/union actual-requires)))))
                 {:required required-js-names
                  :js-properties #{}
                  :live-js-deps #{}
                  :dead-js-deps #{}}))]

      (assoc state
        :js-properties js-properties
        :dead-js-deps dead-js-deps
        :live-js-deps live-js-deps)
      )))
