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
            [clojure.set :as set]
            [cljs.source-map :as sm]
            [shadow.build.npm :as npm]
            [clojure.data.json :as json])
  (:import (java.io StringWriter ByteArrayInputStream FileOutputStream File)
           (com.google.javascript.jscomp JSError SourceFile CompilerOptions CustomPassExecutionTime
                                         CommandLineRunner VariableMap SourceMapInput DiagnosticGroups
                                         CheckLevel JSModule CompilerOptions$LanguageMode
                                         SourceMap$LocationMapping BasicErrorManager Result ShadowAccess
                                         SourceMap$DetailLevel SourceMap$Format ClosureCodingConvention CompilationLevel AnonymousFunctionNamingPolicy)
           (shadow.build.closure ReplaceCLJSConstants)
           (com.google.javascript.jscomp.deps ModuleLoader$ResolutionMode)
           (com.google.javascript.jscomp.parsing.parser FeatureSet)
           (java.nio.charset Charset)))

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
   :common-js-module-load DiagnosticGroups/COMMON_JS_MODULE_LOAD
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

(defn load-externs [{:keys [deps-externs build-modules] :as state}]
  (let [externs
        (distinct
          (concat
            (:externs state)
            (get-in state [:compiler-options :externs])
            ;; needed to get rid of process/global errors in cljs/core.cljs
            ["shadow/cljs/externs/node.js"
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
             (into []))

        foreign-externs
        (->> build-modules
             (mapcat :sources)
             (map #(get-in state [:sources %]))
             (filter util/foreign?)
             (filter :externs-source)
             (map (fn [{:keys [source-path output-name externs externs-source] :as foreign-src}]
                    (SourceFile/fromCode (str "EXTERNS:" source-path "!/" output-name) externs-source)))
             (into []))]

    (->> (concat default-externs deps-externs foreign-externs manual-externs)
         (into []))
    ))

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
                   (data/get-source-by-name state src-name)))]

           (if-not mapping
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

(defn add-input-source-maps [{:keys [build-sources] :as state} cc]
  (doseq [src-id build-sources]
    (let [{:keys [output-name resource-name] :as rc}
          (data/get-source-by-id state src-id)

          {:keys [source-map-json source-map] :as output}
          (data/get-output! state rc)

          source-map-json
          (or source-map-json
              ;; source-map is CLJS source map data, need to encode it to json so closure can read it
              (output/encode-source-map rc output))]

      ;; not using SourceFile/fromFile as the name that gets displayed in warnings sucks
      ;; public/js/cljs-runtime/cljs/core.cljs vs cljs/core.cljs
      (->> (SourceFile/fromCode output-name source-map-json)
           (SourceMapInput.)
           (.addInputSourceMap cc output-name))
      )))

(defn make-foreign-js-header
  "goog.provide/goog.require statements for foreign js files"
  [{:keys [provides deps]}]
  (let [sb (StringBuilder.)]
    (doseq [provide provides]
      (doto sb
        (.append "goog.provide(\"")
        (.append (str (comp/munge provide)))
        (.append "\");\n")))
    (doseq [require deps]
      (doto sb
        (.append "goog.require(\"")
        (.append (str (comp/munge require)))
        (.append "\");\n")))
    (.toString sb)
    ))

;; CLOSURE-WARNING: Property nodeGlobalRequire never defined on goog
;; Original: ~/.m2/repository/org/clojure/clojurescript/1.9.542/clojurescript-1.9.542.jar!/cljs/core.cljs [293:4]
;; cljs/core.cljs contains a call to goog.nodeGlobalRequire(...) which is only used in self-host mode
;; but causes the closure compiler --check to complain
;; since we are never going to use it just emit a noop to get rid of the warning

(def goog-nodeGlobalRequire-fix
  "\ngoog.nodeGlobalRequire = function(path) { return false };\n")

(defn make-js-modules
  [{:keys [build-modules closure-configurators compiler-options] :as state}]

  ;; modules that only contain foreign sources must not be exposed to closure
  ;; since they technically do not depend on goog but closure only allows one root module
  ;; when optimizing
  (let [skip-mods
        (->> build-modules
             (filter :all-foreign)
             (map :module-id)
             (into #{}))

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

              (doseq [{:keys [resource-id resource-name type output-name output] :as rc}
                      (->> sources
                           (map #(data/get-source-by-id state %))
                           (remove util/foreign?))]

                (let [{:keys [js] :as output}
                      (data/get-output! state rc)

                      js
                      (if (not= "goog/base.js" resource-name)
                        js
                        (str (output/closure-defines state)
                             js
                             goog-nodeGlobalRequire-fix))]

                  (when-not (seq js)
                    (throw (ex-info (format "no output for rc: %s" resource-id) output)))

                  ;; foreign files were filtered above
                  (case type
                    :goog
                    (.add js-mod (SourceFile/fromCode output-name js))

                    :npm
                    (.add js-mod (SourceFile/fromCode output-name js))

                    :cljs
                    (.add js-mod (SourceFile/fromCode output-name js))

                    (throw (ex-info (format "unsupported type for closure: %s" type) rc)))))



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
                         goog-nodeGlobalRequire-fix)
                       ;; FIXME: module.exports will become window.module.exports, rewritten later
                       (when (seq defs)
                         defs))

                  js-mod
                  (doto (JSModule. (util/flat-filename output-name))
                    (.add (SourceFile/fromCode output-name code)))]

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

      (.setSourceMapIncludeSourcesContent closure-opts true)
      (.setSourceMapOutputPath closure-opts "/dev/null")
      (.setApplyInputSourceMaps closure-opts true)

      (add-input-source-maps state cc))

    (.addCustomPass closure-opts CustomPassExecutionTime/BEFORE_CHECKS (ReplaceCLJSConstants. cc))

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
      ::externs (load-externs state)
      ::compiler cc
      ::compiler-options closure-opts)))

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
                   (map (fn [{:keys [module-id prepend append output-name js-module sources includes] :as mod}]
                          ;; must reset source map before calling .toSource
                          (when source-map
                            (.reset source-map)

                            ;; this needs to add ALL sources, not just the sources of the module
                            ;; this is because cross-module motion may have moved code
                            ;; closure will only include the relevant files but it needs to be able to find all
                            ;; for some reason .reset removes them all so we need to repeat this for every module
                            ;; since modules require .reset
                            (doseq [src-id (:build-sources state)]
                              (let [{:keys [resource-name source] :as rc}
                                    (get-in state [:sources src-id])]

                                (when (string? source)
                                  (.addSourceFile source-map
                                    (SourceFile/fromCode resource-name source))))))

                          (let [js
                                (if-not js-module ;; foreign only doesnt have JSModule instance
                                  ""
                                  (.toSource compiler js-module))

                                ;; foreign-libs are no disabled for now as they are now covered by either
                                ;; importing them through closure or require
                                #_foreign-js
                                #_(->> sources
                                       (map #(get-in state [:sources %]))
                                       (filter util/foreign?)
                                       (map #(data/get-output! state %))
                                       (map :js)
                                       (str/join "\n"))

                                foreign-js
                                (->> includes
                                     (map (fn [{:keys [name file] :as inc}]
                                            (slurp file)))
                                     (str/join "\n"))

                                js
                                (if (seq foreign-js)
                                  (str foreign-js "\n" js)
                                  js)]

                            (if (and (not (seq js))
                                     (not (seq prepend))
                                     (not (seq append)))
                              (assoc mod :dead true :output "")

                              (-> mod
                                  (assoc :output js)
                                  (cond->
                                    (not= :goog (get-in state [:build-options :module-format]))
                                    (update :output rewrite-node-global-access))

                                  (cond->
                                    (and js-module source-map)
                                    (merge (let [sw (StringWriter.)]

                                             ;; must call .appendTo after calling toSource
                                             (let [lines
                                                   (-> (if (seq prepend)
                                                         (output/line-count prepend)
                                                         0)
                                                       (cond->
                                                         (seq foreign-js)
                                                         (+ (output/line-count foreign-js) 1)
                                                         ;; the npm mode will add one additional line
                                                         ;; for the env setup and requires
                                                         (= :js (get-in state [:build-options :module-format]))
                                                         (inc)))]

                                               (.setStartingPosition source-map lines 0))

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

(defn convert-sources
  "takes a list of :npm sources and rewrites them to closure JS"
  [{:keys [project-dir npm] :as state} sources]
  (util/with-logged-time [state {:type ::convert
                                 :num-sources (count sources)}]

    ;; FIXME: this should do caching but Closure needs all files when compiling
    ;; cannot compile one file at a time with this approach
    ;; CLJS does one at a time but that has other issues
    (let [source-files
          (for [{:keys [resource-name file source] :as src} sources]
            (SourceFile/fromCode resource-name source))

          source-file-names
          (into #{} (map #(.getName %)) source-files)

          source-files
          ;; closure prepends polyfills to the first resource
          ;; adding an empty placeholder ensures we can take them out easily
          (-> [(SourceFile/fromCode polyfill-name "")]
              ;; then add all resources
              (into source-files)
              ;; closure needs package.json files to properly resolve modules
              ;; FIXME: pull request to GCC to allow configuration of packageJsonMainEntries
              ;; currently it always computes those from entries but we already did that
              (into (->> sources
                         (map :package-name)
                         (remove nil?)
                         (distinct)
                         (map #(npm/find-package npm %))
                         ;; sometimes source reference the package.json
                         ;; in that case we can't add it again here since closure
                         ;; does not accept duplicate inputs
                         (remove #(contains? source-file-names (:entry-file %)))
                         (map (fn [{:keys [package-name entry]}]
                                (let [filename
                                      (str "node_modules/" package-name "/package.json")

                                      package-json
                                      (-> {:name package-name
                                           :main entry}
                                          (json/write-str))]

                                  ;; we don't feed the actual package.json file since closure may not recognize
                                  ;; some things we do, eg. "module" instead of "main"
                                  (SourceFile/fromCode filename package-json))
                                )))))

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
            (.setModuleResolutionMode ModuleLoader$ResolutionMode/NODE))

          result
          (try
            (.compile cc [] source-files co)
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

                  ;; if package.json is not referenced in code we don't want to include the output
                  (and (nil? rc)
                       (str/ends-with? name "package.json"))
                  state

                  (nil? rc)
                  (throw (ex-info (format "closure input %s without resource?" name) {}))

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
                         :source-map-json sm-json}]

                    (assoc-in state [:output resource-id] output)))))

            (->> (ShadowAccess/getJsRoot cc)
                 (.children) ;; the inputs
                 ))))))
