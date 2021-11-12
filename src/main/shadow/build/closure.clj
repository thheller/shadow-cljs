(ns shadow.build.closure
  (:require
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [cljs.analyzer :as ana]
    [cljs.compiler :as comp]
    [cljs.env :as env]
    [shadow.cljs.util :as util]
    [shadow.build.output :as output]
    [shadow.build.warnings :as warnings]
    [shadow.build.log :as build-log]
    [shadow.build.data :as data]
    [shadow.build.npm :as npm]
    [shadow.build.cache :as cache]
    [shadow.core-ext :as core-ext]
    [shadow.build.resource :as rc]
    [shadow.debug :refer (?> ?-> ?->>)]
    [clojure.data.json :as json])
  (:import
    [java.io StringWriter ByteArrayInputStream FileOutputStream]
    [com.google.javascript.jscomp
     JSError SourceFile CompilerOptions CustomPassExecutionTime
     CommandLineRunner VariableMap SourceMapInput DiagnosticGroups
     CheckLevel JSChunk CompilerOptions$LanguageMode
     Result ShadowAccess SourceMap$DetailLevel SourceMap$Format
     ClosureCodingConvention CompilationLevel VariableRenamingPolicy
     PropertyRenamingPolicy PhaseOptimizer]
    [shadow.build.closure
     ReplaceCLJSConstants NodeEnvInlinePass ReplaceRequirePass PropertyCollector
     NodeStuffInlinePass FindSurvivingRequireCalls GlobalsAsVar]
    [com.google.javascript.jscomp.deps ModuleLoader$ResolutionMode]
    [java.nio.charset Charset]
    [java.util.logging Logger Level]
    [com.google.javascript.jscomp.parsing.parser FeatureSet]))

;; get rid of some annoying/useless warning log messages
;; https://github.com/google/closure-compiler/pull/2998/files
(doto (Logger/getLogger (.getName PhaseOptimizer))
  (.setLevel Level/OFF))

(def SHADOW-CACHE-KEY
  ;; timestamp to ensure that new shadow-cljs release always invalidate caches
  ;; technically needs to check all files but given that they'll all be in the
  ;; same jar one is enough
  [(data/sha1-url (io/resource "shadow/build/closure.clj"))
   ;; in case someone sets a specific closure dependency
   (data/sha1-url (io/resource "com/google/javascript/jscomp/Compiler.class"))])

(def injected-libraries-field
  (doto (-> (Class/forName "com.google.javascript.jscomp.Compiler")
            (.getDeclaredField "injectedLibraries"))
    (.setAccessible true)))

(def own-symbols-field
  (doto (-> (Class/forName "com.google.javascript.jscomp.SymbolTable$SymbolScope")
            (.getDeclaredField "ownSymbols"))
    (.setAccessible true)))

;;;
;;; partially taken from cljs/closure.clj
;;; removed the things we don't need
;;;
(def check-levels
  {:error CheckLevel/ERROR
   :warning CheckLevel/WARNING
   :off CheckLevel/OFF})

(def warning-types
  (reduce
    (fn [warnings [registered-name group]]
      (let [kw (-> registered-name
                   (str/replace #"[A-Z]" #(str "-" (str/lower-case %)))
                   keyword)]
        (assoc warnings kw group)))
    {}
    (DiagnosticGroups/getRegisteredGroups)))

(defn ^CompilerOptions$LanguageMode lang-key->lang-mode [key]
  (case (keyword (str/replace (name key) #"^es" "ecmascript"))
    :no-transpile CompilerOptions$LanguageMode/NO_TRANSPILE ;; same mode as input (for language-out only)
    :ecmascript3 CompilerOptions$LanguageMode/ECMASCRIPT3
    :ecmascript5 CompilerOptions$LanguageMode/ECMASCRIPT5
    :ecmascript5-strict CompilerOptions$LanguageMode/ECMASCRIPT5_STRICT
    :ecmascript6 CompilerOptions$LanguageMode/ECMASCRIPT_2015 ;; (deprecated and remapped)
    :ecmascript6-strict CompilerOptions$LanguageMode/ECMASCRIPT_2015 ;; (deprecated and remapped)
    :ecmascript-2015 CompilerOptions$LanguageMode/ECMASCRIPT_2015
    :ecmascript-2016 CompilerOptions$LanguageMode/ECMASCRIPT_2016
    :ecmascript-2017 CompilerOptions$LanguageMode/ECMASCRIPT_2017
    :ecmascript-next CompilerOptions$LanguageMode/ECMASCRIPT_NEXT
    :ecmascript-next-in CompilerOptions$LanguageMode/ECMASCRIPT_NEXT_IN))

(defn ^FeatureSet kw->feature-set [kw]
  (case kw
    :bare-minimum FeatureSet/BARE_MINIMUM
    :browser-2020 FeatureSet/BROWSER_2020
    :browser-2021 FeatureSet/BROWSER_2021
    :es3 FeatureSet/ES3
    :es5 FeatureSet/ES5
    ;; FIXME: probably can't allow es6-modules variants since we can never load those
    :es6 FeatureSet/ES2015
    :es7 FeatureSet/ES2017
    :es8 FeatureSet/ES2018
    :es2018 FeatureSet/ES2018
    :es2019 FeatureSet/ES2019
    :es2020 FeatureSet/ES2020
    :es-next FeatureSet/ES_NEXT
    :es-next-in FeatureSet/ES_NEXT_IN
    (throw (ex-info "invalid :output-feature-set" {:val kw}))))

(defn set-options
  [^CompilerOptions closure-opts opts state]

  (when-let [level-kw (:optimizations opts)]
    (when-let [level
               (case level-kw
                 :advanced CompilationLevel/ADVANCED_OPTIMIZATIONS
                 :whitespace CompilationLevel/WHITESPACE_ONLY
                 :simple CompilationLevel/SIMPLE_OPTIMIZATIONS
                 :none nil
                 (throw (ex-info "invalid :optimizations level" opts)))]
      (.setOptionsForCompilationLevel level closure-opts)))

  (when (contains? opts :pretty-print)
    (.setPrettyPrint closure-opts (:pretty-print opts)))

  (when (:source-map opts)
    (doto closure-opts
      (.setSourceMapOutputPath "/dev/null")
      (.setSourceMapDetailLevel
        (let [lvl (:source-map-detail-level opts :all)]
          (case lvl
            :symbols SourceMap$DetailLevel/SYMBOLS
            :all SourceMap$DetailLevel/ALL
            (throw (ex-info "invalid value for :source-map-detail-level option" {:lvl lvl})))))
      (.setSourceMapIncludeSourcesContent
        (boolean (:source-map-include-sources-content opts true)))
      (.setSourceMapFormat SourceMap$Format/V3)))

  (when (contains? opts :pseudo-names)
    (.setGeneratePseudoNames closure-opts (:pseudo-names opts)))

  (when-let [lang-key (:language-in opts)]
    (.setLanguageIn closure-opts (lang-key->lang-mode lang-key)))

  (when-let [lang-key (:language-out opts)]
    (.setLanguageOut closure-opts (lang-key->lang-mode lang-key)))

  (when (:closure-alias-strings opts)
    (.setAliasAllStrings closure-opts true))

  ;; FIXME: maybe make this more customizable. may want es5 but allow class or so
  (when-let [output-feature-set (:output-feature-set opts)]
    (let [fs (kw->feature-set output-feature-set)]
      (.setOutputFeatureSet closure-opts fs)))

  (when-let [extra-annotations (:closure-extra-annotations opts)]
    (. closure-opts (setExtraAnnotationNames (map name extra-annotations))))

  (when (contains? opts :print-input-delimiter)
    (set! (.printInputDelimiter closure-opts) (:print-input-delimiter opts)))

  (when (contains? opts :closure-warnings)
    (doseq [[type level] (:closure-warnings opts)]
      (let [group (get warning-types type)
            check-level (get check-levels level)]
        (if-not (and group check-level)
          (util/warn state {:type ::invalid-closure-warning :key type :level level})
          (. closure-opts (setWarningLevel group check-level))))))

  (when (contains? opts :closure-extra-annotations)
    (. closure-opts (setExtraAnnotationNames (map name (:closure-extra-annotations opts)))))

  (when (contains? opts :closure-generate-exports)
    (. closure-opts (setGenerateExports (:closure-generate-exports opts))))

  (when (contains? opts :rewrite-polyfills)
    (. closure-opts (setRewritePolyfills (:rewrite-polyfills opts))))

  (when (contains? opts :isolate-polyfills)
    (. closure-opts (setIsolatePolyfills (:isolate-polyfills opts))))

  (. closure-opts (setOutputCharset (Charset/forName (:closure-output-charset opts "UTF-8"))))

  (when (contains? opts :variable-renaming)
    (.setVariableRenaming
      closure-opts
      (case (:variable-renaming opts)
        false VariableRenamingPolicy/OFF
        :off VariableRenamingPolicy/OFF
        :local VariableRenamingPolicy/LOCAL
        :all VariableRenamingPolicy/ALL
        (throw (ex-info "invalid :variable-renaming (use :off, :local or :all)" {})))))

  (when (contains? opts :property-renaming)
    (.setPropertyRenaming
      closure-opts
      (case (:property-renaming opts)
        false PropertyRenamingPolicy/OFF
        :off PropertyRenamingPolicy/OFF
        true PropertyRenamingPolicy/ALL_UNQUOTED
        :all-unquoted PropertyRenamingPolicy/ALL_UNQUOTED
        (throw (ex-info "invalid :property-renaming (use :off or :all-unquoted)" {})))))

  ;; required to fix https://clojure.atlassian.net/browse/CLJS-3207
  ;; sometimes creates problems with reify + multiple :modules
  (when (contains? opts :cross-chunk-method-motion)
    (.setCrossChunkMethodMotion closure-opts (:cross-chunk-method-motion opts)))

  (when (contains? opts :rename-prefix)
    (.setRenamePrefix closure-opts (:rename-prefix opts)))

  (when (contains? opts :rename-prefix-namespace)
    (.setRenamePrefixNamespace closure-opts (:rename-prefix-namespace opts)))

  closure-opts)

(defn ^CompilerOptions make-options []
  (doto (CompilerOptions.)
    (.setCodingConvention (ClosureCodingConvention.))))

(def default-externs
  (delay (into [] (CommandLineRunner/getDefaultExterns))))

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
         Worker
         XMLHttpRequest
         window
         __filename
         __dirname]
       (into #{} (map str))))

(defn clean-collected-properties [properties]
  ;; some files may have been minified by closure
  ;; which follows this naming pattern and we can't reserve those
  ;; since we need them for our :advanced build later
  (if-not (set/superset? properties #{"a" "b" "c" "d" "e" "f"})
    properties
    ;; just strip out the "short" properties
    (into #{} (remove #(<= (count %) 2)) properties)))

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

(defn load-simplified-externs [state]
  (let [[file-props file-globals]
        (externs-for-build state)

        content
        (str (->> file-globals
                  (map #(comp/munge % #{}))
                  (sort)
                  (map #(str "/** @const {ShadowJS} */ var " % ";"))
                  (str/join "\n"))
             "\n"
             (->> file-props
                  (sort)
                  (map #(comp/munge % #{}))
                  (map #(str "ShadowJS.prototype." % ";"))
                  (str/join "\n")))]

    (SourceFile/fromCode "simplified-externs.js" content)))

(defn generate-externs [state]
  (let [js-externs?
        (and (true? (get-in state [:js-options :generate-externs]))
             (= :shadow (get-in state [:js-options :js-provider])))

        js-props
        (set/union
          (when js-externs?
            (:js-properties state))
          (extern-props-from-cljs state))

        js-globals
        (set/union
          #{"module"}
          (extern-globals-from-cljs state))

        content
        (str (->> js-globals
                  (remove known-js-globals)
                  (map #(comp/munge % #{}))
                  (sort)
                  (map #(str "/** @const {ShadowJS} */ var " % ";"))
                  (str/join "\n"))
             "\n"
             (->> js-props
                  (sort)
                  (map #(comp/munge % #{}))
                  (map #(str "ShadowJS.prototype." % ";"))
                  (str/join "\n")))]

    ;; not actually required but makes it easier to verify
    (let [file (data/cache-file state "externs.shadow.js")]
      (io/make-parents file)
      (spit file content))

    (SourceFile/fromCode "externs.shadow.js" content)
    ))

(defn load-externs [{:keys [build-modules deps-externs] :as state} generate?]
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
                     (do (util/warn state {:type :missing-externs :extern ext})
                         nil))))
               (remove nil?)
               ;; just to force the logging
               (into [])))

        deps-externs
        (when (get-in state [:build-options :cljsjs-externs])
          (->> (for [[deps-path externs] deps-externs
                     {:keys [resource-name url] :as ext} externs]
                 ;; FIXME: use url? deps-path is accurate enough for now
                 (SourceFile/fromCode (str "EXTERNS:" deps-path "!/" resource-name) (slurp url)))
               (into [])))

        simplified-externs
        (load-simplified-externs state)

        module-externs
        (->> build-modules
             (mapcat :module-externs)
             (map #(str "var " % ";"))
             (str/join "\n"))

        all-externs
        (-> []
            (into @default-externs)
            (into deps-externs)
            (into manual-externs)
            ;; closure gets picky when things are defined multiple times
            ;; both the simplified and generated externs need this
            (conj (SourceFile/fromCode
                    "externs-header.shadow.js"
                    "/** @constructor */\nfunction ShadowJS() {};\n"))
            (conj simplified-externs)
            (cond->
              (seq module-externs)
              (conj (SourceFile/fromCode "externs.modules.js" module-externs))

              generate?
              (conj (generate-externs state))))]

    all-externs))

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
          (.getGlobalType type-reg "Object")]

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
          (util/error state {:type ::map-load-error :file map-file :ex e})
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

(defmethod build-log/event->str ::warnings
  [{:keys [warnings]}]
  (warnings/print-closure-warnings warnings))

(defn js-error-xf [state ^com.google.javascript.jscomp.Compiler cc]
  (map (fn [^JSError err]
         (let [source-name
               (.getSourceName err)

               description
               (.getDescription err)

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
              :msg description}

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
                   (warnings/get-source-excerpt-for-rc state src {:line line :column column})]

               {:resource-id resource-id
                :resource-name resource-name
                :source-name source-name
                :file file
                :line line
                :column column
                :msg description
                :source-excerpt source-excerpt}
               ))))))

(defn closure-source-file
  ([name code]
   (SourceFile/fromCode name code))
  ([name code original-name]
   ;; closure removed the ability to provide originalPath
   ;; IIRC this was only used to make npm source maps slightly more accurate
   ;; by pointing to the actual file rather than the resource name we
   ;; assigned it. can't remember if this had any other effect.
   #_(-> (SourceFile/builder)
         (.withOriginalPath original-name)
         (.buildFromCode name code))
   (SourceFile/fromCode name code)
   ))

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
              (output/encode-source-map-json state rc output))]

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

(def constants-inject
  (str "function shadow$keyword(name) { return new cljs.core.Keyword(null, name, name, null); };\n"
       "function shadow$keyword_fqn(ns, name) { return new cljs.core.Keyword(ns, name, ns + \"/\" + name, null); };\n"))

(defn make-js-modules
  [{:keys [build-modules build-sources] :as state}]

  (let [required-js-names
        (data/js-names-accessed-from-cljs state build-sources)

        js-mods
        (reduce
          (fn [js-mods {:keys [module-id output-name depends-on sources goog-base] :as mod}]
            (let [js-mod (JSChunk. output-name)

                  sources
                  (->> sources
                       (map #(data/get-source-by-id state %)))

                  mod-provides
                  (->> sources
                       (map :provides)
                       (reduce set/union #{}))

                  mod-deps
                  (->> sources
                       (mapcat #(data/deps->syms state %))
                       (into #{}))

                  mod-constants-name
                  (str "shadow/cljs/constants/" (name module-id) ".js")]

              (doseq [other-mod-id depends-on
                      :let [other-mod (get js-mods other-mod-id)]]
                (when-not other-mod
                  (throw (ex-info (format "module \"%s\" depends on undefined module \"%s\", defined modules are %s"
                                    module-id
                                    other-mod-id
                                    (set (map :module-id build-modules)))

                           {:id module-id :other other-mod-id})))

                (.addDependency js-mod other-mod))

              ;; every module that does not contain cljs.core will get a .constants js files as its first input
              ;; so the ReplaceCLJSConstants pass can write them into that file
              ;; special case for cljs.core since we shouldn't create keywords before they are defined
              (when (and (contains? mod-deps 'cljs.core)
                         (not (contains? mod-provides 'cljs.core)))
                (.add js-mod (closure-source-file mod-constants-name "")))

              ;; closure for some reason creates
              ;;   module$demo$es6 = { ... };
              ;; directly without ever declaring a var for it. not sure why.
              ;; results in
              ;;   Resource: demo/browser.cljs:2:18
              ;;   variable module$demo$es6 is undeclared
              ;; warnings for each used module
              (let [module-vars
                    (->> sources
                         (filter #(= :js (:type %)))
                         (map :ns)
                         (map comp/munge)
                         (str/join ","))]
                (when (seq module-vars)
                  (.add js-mod (closure-source-file
                                 (str "shadow/cljs/module_vars/" (name module-id) ".js")
                                 ;; var module$a,module$b,...; they will be renamed by advanced
                                 (str "var " module-vars ";\n")))))

              (doseq [{:keys [resource-name ns type] :as rc}
                      sources]
                (let [{:keys [js]}
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

                  (.add js-mod (closure-source-file resource-name js))

                  (when (= ns 'cljs.core)
                    (.add js-mod (closure-source-file mod-constants-name constants-inject)))
                  ))

              (assoc js-mods module-id js-mod)))
          {}
          build-modules)

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

  (let [^JSChunk goog-mod
        (JSChunk. "cljs_env.js")

        goog-sources
        (->> build-sources
             (map #(data/get-source-by-id state %))
             (filter :goog-src)
             (into []))

        goog-src-ids
        (->> goog-sources
             (map :resource-id)
             (into #{}))

        js-mods
        (reduce
          (fn [js-mods resource-id]
            (let [{:keys [ns deps type resource-name output-name] :as src}
                  (data/get-source-by-id state resource-id)

                  {:keys [js] :as output}
                  (data/get-output! state {:resource-id resource-id})]

              (cond
                (= output/goog-base-id resource-id)
                (let [code
                      (str (output/closure-defines state)
                           js
                           (str goog-nodeGlobalRequire-fix "\ngoog.global = global;"))]

                  (.add goog-mod (closure-source-file resource-name code))
                  js-mods)

                (:goog-src src)
                (do (.add goog-mod (closure-source-file resource-name js))
                    js-mods)

                :else
                (let [defs
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
                                    (let [export-name (-> def name str)
                                          export-name
                                          (if (= "default" export-name)
                                            export-name
                                            (-> export-name (comp/munge) (core-ext/safe-pr-str)))]
                                      (str export-name ":" (comp/munge def)))))
                             (str/join ",")
                             (as-module-exports))

                        :else
                        nil)

                      code
                      (str js
                           (when (seq defs)
                             defs))

                      js-mod
                      (JSChunk. (util/flat-filename output-name))]

                  (when (= :cljs type)
                    (.add js-mod (closure-source-file (str "shadow/cljs/constants/" resource-name) "")))

                  (.add js-mod (closure-source-file resource-name code))

                  (.addDependency js-mod goog-mod)

                  (doseq [dep
                          (->> (data/deps->syms state src)
                               (map #(data/get-source-id-by-provide state %))
                               (distinct)
                               (into []))
                          :when (not (contains? goog-src-ids dep))]

                    (let [other-mod (get js-mods dep)]
                      (when-not other-mod
                        (throw (ex-info (format "internal module error, no mod for dep:%s" dep)
                                 {:dep dep})))
                      (.addDependency js-mod other-mod)))

                  (assoc js-mods resource-id js-mod)))))

          {}
          build-sources)

        modules
        (->> build-sources
             (map #(data/get-source-by-id state %))
             (remove :goog-src)
             (map (fn [{:keys [resource-id] :as src}]
                    {:module-id resource-id
                     :output-name (util/flat-filename (:output-name src))
                     :js-module (get js-mods resource-id)
                     :sources [resource-id]}))
             (into [{:module-id output/goog-base-id
                     :output-name "cljs_env.js"
                     :js-module goog-mod
                     :sources (->> goog-sources
                                   (map :resource-id)
                                   (into []))}]))]

    (assoc state ::modules modules)))

(defn setup
  [{::keys [modules]
    :keys [closure-injected-libs compiler-options] :as state}]
  (let [source-map?
        (boolean (:source-map compiler-options))

        cc
        (data/make-closure-compiler)

        closure-opts
        (doto (make-options)
          (.resetWarningsGuard)
          (.setNumParallelThreads (get-in state [:compiler-options :closure-threads] 1))

          ;; namespaced keywords will cause a lot of repetition for namespace strings
          ;; allowing closure to alias them can cut the overall size a bit
          ;; gzip actually takes quite good care of this already but this squeezes
          ;; another few bytes out for no additional work

          ;; this was removed in closure without much clarification for why
          ;; https://github.com/google/closure-compiler/commit/752b975a8eb7d4c71ac5550039451a23a000175d
          #_(.setAliasableStrings
              (->> (:compiler-env state)
                   (::ana/namespaces)
                   (keys)
                   (map str)
                   (set)))

          (.setWarningLevel DiagnosticGroups/CHECK_TYPES CheckLevel/OFF)
          ;; really only want the undefined variables warnings
          ;; must turn off CHECK_VARIABLES first or it will complain too much (REDECLARED_VARIABLES)
          (.setWarningLevel DiagnosticGroups/CHECK_VARIABLES CheckLevel/OFF)
          ;; this one is very helpful to spot missing externs
          ;; (js/React...) will otherwise just work without externs
          (.setWarningLevel DiagnosticGroups/UNDEFINED_VARIABLES CheckLevel/WARNING))]

    (set-options closure-opts compiler-options state)

    ;; ensure that libs injected during transforms are injected again
    ;; not using polyfill-js since we might need to inject polyfills for CLJS
    ;; which wasn't processed yet and would clash with the manually injected libs
    (let [injected-libs
          (set/union
            #{}
            closure-injected-libs
            (get-in state [:compiler-options :force-library-injection]))]
      (when (seq injected-libs)
        (doto closure-opts
          (.setRewritePolyfills true)
          (.setForceLibraryInjection injected-libs))))

    ;; add-input-source-maps requires options to be set, otherwise throws NPE
    ;; ShadowCompiler exposes this to just set options since initOptions does a bunch of stuff
    ;; we do not need yet
    (.initOptions cc closure-opts)

    (.setApplyInputSourceMaps closure-opts true)
    (add-input-source-maps state cc)

    (.addCustomPass closure-opts CustomPassExecutionTime/BEFORE_CHECKS
      (ReplaceCLJSConstants. cc (true? (get-in state [:compiler-options :shadow-keywords]))))
    (.addCustomPass closure-opts CustomPassExecutionTime/BEFORE_CHECKS
      (NodeEnvInlinePass. cc (if (= :release (:mode state))
                               "production"
                               "development")))


    ;; (fn [closure-compiler compiler-options state])
    (doseq [cfg (:closure-configurators compiler-options)]
      (cfg cc closure-opts state))

    (when (= :js (get-in state [:build-options :module-format]))

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
    (let [cc (data/make-closure-compiler)
          co
          (doto (make-options)
            (set-options
              {:optimizations :simple
               :language-in :ecmascript5}
              state))

          externs
          (load-externs state false)

          result
          (.compile cc externs [] co)

          ;; already immutable set, but incompatible
          ;; com.google.common.collect.RegularImmutableSet cannot be cast to clojure.lang.IPersistentCollection
          extern-properties
          (into #{} (ShadowAccess/getExternProperties cc))]

      (when-not (.success result)
        (throw (ex-info
                 (str "Error processing externs:\n"
                      (->> (.errors result)
                           (map str)
                           (str/join "\n")))
                 {:tag ::load-externs-failed})))

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
      (str/replace "window.global", "/*****/global")
      ))

(defn source-map-sources [state]
  (->> (:build-sources state)
       (map (fn [src-id]
              (let [{:keys [resource-name] :as rc}
                    (get-in state [:sources src-id])

                    {:keys [source]}
                    (data/get-output! state rc)]

                (when (string? source)
                  (closure-source-file resource-name source)))))
       (remove nil?)
       (into [])))

(defn add-sources-to-source-map [state source-map]
  ;; this needs to add ALL sources, not just the sources of the module
  ;; this is because cross-module motion may have moved code
  ;; closure will only include the relevant files but it needs to be able to find all
  ;; for some reason .reset removes them all so we need to repeat this for every module
  ;; since modules require .reset
  (doseq [src-file (source-map-sources state)]
    (.addSourceFile source-map (.getName src-file) (.getCode src-file))))

(defn get-injected-libs [compiler]
  (-> (.get injected-libraries-field compiler)
      (set)))

(defn get-own-symbol-names [symbol-scope]
  (-> (.get own-symbols-field symbol-scope)
      (keys)
      (set)))

(defn dump-closure-inputs [state externs js-mods compiler-options]
  (doseq [extern externs]
    ;; externs have weird filenames and we don't need to keep folders intact
    ;; eg externs.zip//v8.js so we munge
    (let [ext-file (data/cache-file state "closure-inputs" "__externs" (munge (.getName extern)))]
      (io/make-parents ext-file)
      (spit ext-file (.getCode extern))))

  (let [opts-file (data/cache-file state "closure-inputs" "__compiler_opts.txt")]
    ;; not exactly great output but I want the actual options used by closure
    ;; not their CLJS representation
    (spit opts-file (str compiler-options)))

  (doseq [js-mod js-mods]
    (let [mod-file (data/cache-file state "closure-inputs" (.getName js-mod))]
      (io/make-parents mod-file)
      (spit mod-file
        (->> (.getInputs js-mod)
             (map #(.getName %))
             (str/join "\n"))))

    (doseq [input (.getInputs js-mod)]
      (let [input-file (data/cache-file state "closure-inputs" (.getName input))]
        (io/make-parents input-file)
        (spit input-file (.getCode input))))))


(defn compile-js-modules
  [{::keys [externs modules compiler compiler-options] :as state}]
  (let [js-mods
        (->> modules
             (map :js-module)
             (remove nil?)
             (into []))

        ;; _ (dump-js-modules js-mods)
        _
        (when (get-in state [:compiler-options :dump-closure-inputs])
          (dump-closure-inputs state externs js-mods compiler-options))

        ^Result result
        (.compileModules
          compiler
          externs
          js-mods
          compiler-options)

        success?
        (.success result)

        ;; keep track of all the injected polyfills for some reports?
        injected-libs
        (get-injected-libs compiler)

        source-map
        (when (and success? (get-in state [:compiler-options :source-map]))
          (.getSourceMap compiler))]

    (-> state
        (assoc ::result result ::injected-libs injected-libs)
        (cond->
          success?
          (update ::modules
            (fn [modules]
              (->> modules
                   (map (fn [{:keys [goog-base module-id prepend append output-name js-module sources includes] :as mod}]
                          ;; must reset source map before calling .toSource
                          (when source-map
                            (.reset source-map)
                            (when (get-in state [:compiler-options :source-map-include-sources-content])
                              (add-sources-to-source-map state source-map)))

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
       (util/warn state {:type ::warnings
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

(defn get-js-module-requires [{::keys [dead-modules] :as state} ^JSChunk js-module]
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

(defmethod build-log/event->str ::closure-convert
  [{:keys [num-sources] :as event}]
  (format "Closure JS converting %d JS sources" num-sources))

(defmethod build-log/event->str ::shadow-convert
  [{:keys [num-sources] :as event}]
  (format "Shadow JS converting %d JS sources" num-sources))

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
  [state {:keys [ns js-str-offsets] :as rc} source]
  (let [aliases
        (get-in state [:str->sym ns])]

    (reduce
      (fn [source {:keys [string offset] :as require-loc}]
        (let [alias (get aliases string)]
          (if-not alias ;; goog:cljs.core is not aliased
            source
            (let [{:keys [resource-name ns type js-require] :as other-rc}
                  (data/get-source-by-provide state alias)

                  replacement
                  (if (and (:import require-loc) (or (= :goog type) (= :shadow-js type)))
                    ;; goog supports import ... from "goog:cljs.core" in ES6 files
                    ;; but not for commonjs require
                    (str "goog:" (comp/munge ns))
                    (str "/" resource-name))]

              (str (subs source 0 (inc offset))
                   replacement
                   (subs source (+ 1 offset (count string))))
              ))))
      source
      ;; must start at the end since we are inserting longer strings
      (reverse js-str-offsets))))

;; closure folks really do not care about backwards compatibility
;; SourceMap$LocationMapping used to be a final class
;; v20180506 release contains PrefixLocationMapping with LocationMapping becoming an interface
;; but it was rolled back shortly after release to the older version, not sure what it will be in the future
(def make-location-mapping
  (let [class
        (try
          ;; https://github.com/google/closure-compiler/commit/985fc0ccea83fc8d90c2301ba56a9831e06dbd8e
          (Class/forName "com.google.javascript.jscomp.SourceMap$PrefixLocationMapping")
          (catch ClassNotFoundException ex
            ;; https://github.com/google/closure-compiler/commit/5d60d64275a24ff2d93a5db778d8017fce76ad99
            (Class/forName "com.google.javascript.jscomp.SourceMap$LocationMapping")))

        ctor
        (.getConstructor class (into-array [String String]))]

    (fn [resource-name file-path]
      (.newInstance ctor (into-array [resource-name file-path]))
      )))

(defn add-sm-location-mappings [co state sources]
  ;; can't feed the actual path to GCC when compiling since
  ;; that affects which variable GCC chooses in the code
  ;; just mapping each file back to the file this way
  ;; which is not exactly the intent of prefix mappings but it works
  (when (true? (get-in state [:compiler-options :source-map-use-fs-paths]))
    (.setSourceMapLocationMappings
      co
      (->> (for [{:keys [resource-name file]} sources
                 :when file]
             (make-location-mapping resource-name (.getAbsolutePath file)))
           (into [])))))

(defn get-output-options [state]
  (merge
    (when-let [lo (get-in state [:compiler-options :language-out])]
      {:language-out lo})
    (when-let [ofs (get-in state [:compiler-options :output-feature-set])]
      {:output-feature-set ofs})
    (when-let [charset (get-in state [:compiler-options :closure-output-charset])]
      {:closure-output-charset charset})))

(def cache-affecting-options
  [[:compiler-options :source-map-use-fs-paths]
   [:compiler-options :rewrite-polyfills]
   [:compiler-options :language-out]
   [:compiler-options :output-feature-set]
   [:js-options]])

(defn get-field-value [o field-name]
  (let [fld (-> o (.getClass) (.getDeclaredField field-name))]
    (.setAccessible fld true)
    (.get fld o)))

(defn set-field-value [o field-name val]
  (let [fld (-> o (.getClass) (.getDeclaredField field-name))]
    (.setAccessible fld true)
    (.set fld o val)))

(defn convert-sources*
  "takes a list of :js sources and rewrites them to using closure
   partial compiles must supply the cached sources so closure doesn't complain about things
   it cannot resolve"
  [{:keys [mode] :as state} sources cache-files]

  (let [source-files
        (for [{:keys [resource-name file] :as src} sources]
          (let [source
                (data/get-source-code state src)
                source
                (replace-file-references state src source)]
            (closure-source-file resource-name source)))

        cache-file-names
        (into #{} (map :resource-name) cache-files)

        required-shadow-js
        (->> sources
             (mapcat #(data/deps->syms state %))
             (into #{}))

        shadow-js-files
        (->> required-shadow-js
             (map #(data/get-source-by-provide state %))
             (filter #(not= :js (:type %)))
             (map (fn [{:keys [resource-name ns type] :as rc}]
                    (closure-source-file resource-name
                      (case type
                        ;; just in case we add the goog.provide for cljs/closure namespaces
                        (:cljs :goog :shadow-js)
                        (str "goog.provide(\"" (comp/munge ns) "\");")
                        (throw (ex-info (format "whats this? %s type: %s" resource-name type) {}))))))
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
        (data/make-closure-compiler)

        ;; FIXME: are there more options we should take from the user?
        co-opts
        (merge
          {:pretty-print true
           :source-map true
           :language-in :ecmascript-next-in
           :output-feature-set :es6}
          (get-output-options state))

        co
        (doto (make-options)
          (set-options co-opts state)
          (add-sm-location-mappings state sources))

        co
        (if (false? (get-in state [:compiler-options :rewrite-polyfills]))
          co
          (doto co
            (.setRewritePolyfills true)
            (.setPreventLibraryInjection false)))

        property-collector
        (PropertyCollector. cc)

        co
        (doto co
          (.setWarningLevel DiagnosticGroups/NON_STANDARD_JSDOC CheckLevel/OFF)

          (.addCustomPass CustomPassExecutionTime/AFTER_OPTIMIZATION_LOOP property-collector)

          (.setProcessCommonJSModules true)
          ;; officially deprecated and shouldn't be used anymore
          ;; (.setTransformAMDToCJSModules true)
          ;; just in case there are some type annotations
          (.setPreserveTypeAnnotations true)

          (.setApplyInputSourceMaps true)
          (.setSourceMapIncludeSourcesContent true)

          (.setNumParallelThreads (get-in state [:compiler-options :closure-threads] 1))
          (.setModuleResolutionMode ModuleLoader$ResolutionMode/BROWSER))

        ;; workaround for https://github.com/thheller/shadow-cljs/issues/894
        ;; can't have const foo$$module$some$es6 in eval-loaded code since we need
        ;; to be able to redefine it and have it available globally
        _
        (when (= :dev (:shadow.build/mode state))
          (.addCustomPass co CustomPassExecutionTime/AFTER_OPTIMIZATION_LOOP (GlobalsAsVar. cc)))

        result
        (try
          (util/with-logged-time [state {:type ::closure-convert
                                         :num-sources (count sources)}]

            (.compile cc @default-externs source-files co))
          (catch Exception e
            (throw (ex-info "failed to convert sources"
                     {:tag ::convert-error
                      :sources (into [] (map :resource-id) sources)}
                     e))))

        source-map
        (.getSourceMap cc)

        injected-libs
        (get-injected-libs cc)

        global-symbol-names
        (-> (.buildKnownSymbolTable cc)
            (.getGlobalScope)
            (get-own-symbol-names))

        eval-in-global-scope
        (get state ::output/eval-in-global-scope true)]

    (throw-errors! state cc result)

    (-> state
        (log-warnings cc result)
        (update ::classpath-js-injected-libs set/union injected-libs)
        (util/reduce->
          (fn [state source-node]
            (.reset source-map)

            (let [name (.getSourceFileName source-node)]

              (cond
                (= polyfill-name name)
                state

                (contains? cache-file-names name)
                ;; don't update output for cached file, otherwise will cause
                ;; them all to be reloaded and written to disk although they shouldn't have changed
                ;; and were only fed to GCC because incremental compiles are bad
                state

                ;; actually compiled files record :output
                :else
                (let [source-file
                      (get source-files-by-name name)

                      {:keys [resource-id output-name ns] :as rc}
                      (get resource-by-name name)]

                  (cond
                    (nil? rc)
                    state

                    :else
                    (let [js
                          (try
                            (ShadowAccess/nodeToJs cc source-map source-node)
                            (catch Exception e
                              (throw (ex-info (format "failed to generate JS for \"%s\"" name) {:name name} e))))

                          shadow-js-prefix
                          (when (= :dev mode)
                            (let [shadow-js-deps
                                  (->> (for [dep-sym (data/deps->syms state rc)
                                             :let [{:keys [type ns] :as dep} (data/get-source-by-provide state dep-sym)]
                                             :when (= :shadow-js type)]
                                         dep)
                                       (map (fn [{:keys [ns] :as require-rc}]
                                              (str "var " ns " = "
                                                   (npm/shadow-js-require require-rc))))
                                       (str/join "\n"))]
                              (when (seq shadow-js-deps)
                                (str shadow-js-deps "\n"))))

                          sw
                          (StringWriter.)

                          _
                          (doto source-map
                            ;; for sourcesContent
                            (.addSourceFile (.getName source-file) (.getCode source-file))
                            (.setWrapperPrefix (or shadow-js-prefix ""))
                            (.appendTo sw output-name))

                          ;; must ensure that all shadow-js deps are properly required during dev
                          ;; as closure just emits the reference directly
                          js
                          (str shadow-js-prefix js)

                          ;; closure creates var bar$$module$some$file
                          ;; for export let bar = 1 in /some/file.js
                          ;; which is fine if everything is in the same global scope
                          ;; but for node they are in their own local scope so we need to lift them into the global scope later
                          sym-str
                          (str ns)

                          names-in-this-file
                          (->> global-symbol-names
                               (filter #(str/ends-with? % sym-str))
                               (set))

                          sm-json
                          (.toString sw)

                          properties
                          (-> (into #{} (-> property-collector (.-properties) (.get name)))
                              (clean-collected-properties))

                          output
                          {:resource-id resource-id
                           :compiled-at (System/currentTimeMillis)
                           :js (str js
                                    ;; closure only declares var module$src$dev$demo$es6 = {};
                                    ;; but since we are in separate files that var is not available globally
                                    ;; in node envs. this doesn't hurt the browser so its fine.
                                    ;; dont need it in release since everything is one file after
                                    (when (and (= :dev mode)
                                               ;; closure won't generate the alias for files without exports
                                               ;; looking for this pattern
                                               ;; var module$demo$reducers$profile = ...
                                               ;; FIXME: not sure if it will always emit this exact pattern
                                               (str/includes? js (str "var " ns " =")))
                                      (str "\n$CLJS." ns "=" ns ";"))

                                    ;; actually lift the names into the global scope if needed
                                    ;; normally not required since everything is in the same scope
                                    ;; mostly node will have its own scope per file though
                                    (when (and (= :dev mode) (not eval-in-global-scope) (seq names-in-this-file))
                                      (str "\n"
                                           (->> names-in-this-file
                                                (map #(str "global." % " = " % ";"))
                                                (str/join "\n")))))

                           :js-symbol-names names-in-this-file
                           :properties properties
                           :source (.getCode source-file)
                           :source-map-json sm-json}]

                      (-> state
                          (assoc-in [:output resource-id] output)
                          (update :js-properties set/union properties))))))))

          (->> (ShadowAccess/getJsRoot cc)
               (.children) ;; the inputs
               )))))

;; unfortunately we can't do caching for this since the closure compiler
;; doesn't do incremental compiles well. it will attempt to resolve
;; deps which is a problem when we don't pass in everything.
;; if we pass in the compiled code and only recompile what is needed
;; the polyfills won't match up and since we only get them as a complete
;; string...
(defn convert-sources
  "convert and caches closure js sources"
  [state sources]
  (let [cache-index-file
        (data/cache-file state "closure-js" "index.json.transit")

        cache-index
        (or (::closure-js-cache state)
            ;; read from disk
            (and (.exists cache-index-file)
                 (cache/read-cache cache-index-file))
            ;; ensure that at least the directoy exists so we can write files into it later
            (do (io/make-parents cache-index-file)
                {}))

        cache-options
        (->> cache-affecting-options
             (map #(get-in state %))
             (into []))

        cache-files
        (if (or (not= SHADOW-CACHE-KEY (:SHADOW-CACHE-KEY cache-index))
                (not= cache-options (:CACHE-OPTIONS cache-index)))
          ;; invalidate all cache when the timestamp of this file has changed
          []
          ;; compare each cache key
          (->> (for [{:keys [resource-id cache-key] :as src} sources
                     :when (and (= cache-key (get cache-index resource-id))
                                (get-in state [:output resource-id]))]
                 src)
               ;; need to preserve order for later
               (into [])))

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
          ;; restore cached output, cannot do incremental compiled due to closure being picky about names
          (util/with-logged-time [state {:type ::closure-cache-read
                                         :num-files (count sources)}]
            (-> state
                (update ::classpath-js-injected-libs set/union (:injected-libs cache-index))
                (util/reduce->
                  (fn [state {:keys [resource-id output-name] :as cached-rc}]
                    (if (get-in state [:output resource-id])
                      state
                      (let [cache-file
                            (data/cache-file state "closure-js" output-name)

                            {:keys [actual-requires properties] :as cached-output}
                            (-> (cache/read-cache cache-file)
                                (assoc :cached true))]

                        (-> state
                            (assoc-in [:output resource-id] cached-output)
                            (update :js-properties set/union properties))
                        )))
                  cache-files)))
          ;; compile all again
          (convert-sources* state sources cache-files))

        cache-index-updated
        (if-not need-compile?
          cache-index
          (util/with-logged-time [state {:type ::closure-cache-write
                                         :num-files (count sources)}]
            (-> cache-index
                (assoc :SHADOW-CACHE-KEY SHADOW-CACHE-KEY
                       :CACHE-OPTIONS cache-options
                       :injected-libs (::classpath-js-injected-libs state))
                (util/reduce->
                  (fn [idx {:keys [cache-key resource-id output-name] :as compiled-src}]
                    (let [output
                          (data/get-output! state compiled-src)

                          cache-file
                          (data/cache-file state "closure-js" output-name)]

                      (cache/write-file cache-file output)

                      (assoc idx resource-id cache-key)))
                  sources))))]

    (when need-compile?
      (cache/write-file cache-index-file cache-index-updated))

    (assoc state ::closure-js-cache cache-index-updated)
    ))

(defn require-replacement-map [{:keys [str->sym sym->id] :as state}]
  (reduce-kv
    (fn [m ns require-map]
      (let [rc-id
            (data/get-source-id-by-provide state ns)
            {:keys [resource-name] :as rc}
            (data/get-source-by-id state rc-id)

            require-map
            (if-not (get-in state [:js-options :minimize-require])
              require-map
              (reduce-kv
                (fn [m require target]
                  (let [src-id (get sym->id target)
                        src (get-in state [:sources src-id])]
                    (if-not (and src (:require-id src))
                      m
                      (assoc m require (:require-id src)))))
                require-map
                require-map))]

        (assoc m resource-name require-map)))
    {}
    str->sym))

(defn cleanup-package-json-source
  "npm adds a bunch of keys to package.json on install. none of those should ever be relevant in code
   eg. _where _resolve _shasum"
  [source]
  (let [obj (json/read-str source)]
    (-> (reduce-kv
          (fn [m k v]
            (if-not (str/starts-with? k "_")
              m
              (dissoc m k)))
          obj
          obj)
        (json/write-str))))

(defn hide-closure-defines [source]
  ;; source sources like @firebase/util contain
  ;;   @define {boolean}
  ;; which Closure interprets as being closure-defines which they seem to be
  ;; but somehow weren't actually replaced or set during their build
  ;; Closure errors out when we process it so we just hide it in a way that
  ;; Closure doesn't complain anymore
  (str/replace source #"\* @define" "* .define"))

(defn convert-sources-simple*
  "takes a list of :npm sources and rewrites in a browser compatible way, no full conversion"
  [{:keys [js-options mode] :as state} sources]
  (let [source-files
        (->> (for [{:keys [resource-id resource-name ns require-id file deps] :as src} sources]
               (let [source (data/get-source-code state src)
                     source-file
                     (closure-source-file
                       resource-name
                       (str "shadow$provide["
                            (if (and require-id (:minimize-require js-options))
                              (pr-str require-id)
                              (str "\"" ns "\""))
                            "] = function(global,require,module,exports) {\n"
                            (when (:uses-global-process src)
                              "var process = require('process');\n")
                            (when (:uses-global-buffer src)
                              "var Buffer = require('buffer').Buffer;\n")
                            (cond
                              (str/ends-with? resource-name "package.json")
                              (str "module.exports=(" (cleanup-package-json-source source) ");")

                              (str/ends-with? resource-name ".json")
                              (str "module.exports=(" source ");")

                              :else
                              (hide-closure-defines source))
                            "\n};")
                       (when file
                         (.getAbsolutePath file)))]

                 source-file))
             #_(map #(do (println (.getCode %)) %))
             (into [(closure-source-file polyfill-name "")]))

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
        (data/make-closure-compiler)

        co-opts
        (merge
          ;; :whitespace or :simple work but some patterns really require the DCE done by :simple
          ;; eg some conditional imports done by react&friends
          {:optimizations :simple
           :pretty-print false
           :pseudo-names false
           ;; es6+ is transformed by babel first
           ;; but closure got real picky and complains about some things being es6 that aren't
           ;; doesn't really impact much here anyways
           :language-in :ecmascript-next-in
           :output-feature-set :es6}
          (get-output-options state)
          ;; js-options should always override things pulled from :compiler-options
          (dissoc js-options :resolve)
          ;; always enable source-map, just skip emitting them later if desired
          {:source-map true})

        generate-source-map?
        (not (false? (:source-map js-options)))

        property-collector
        (PropertyCollector. cc)

        require-replacements
        (require-replacement-map state)

        replace-require-pass
        (ReplaceRequirePass. cc require-replacements)

        closure-opts
        (doto (make-options)
          (set-options co-opts state)
          (add-sm-location-mappings state sources)

          (.resetWarningsGuard)
          ;; we are processing wild npm code here that doesn't use closure-compiler annotations
          ;; @export shouldn't try to call goog specific code
          (.setGenerateExports false)

          (.setStrictModeInput false)

          (.addCustomPass CustomPassExecutionTime/BEFORE_CHECKS
            (NodeEnvInlinePass. cc (if (= :release mode)
                                     "production"
                                     "development")))

          (.addCustomPass CustomPassExecutionTime/BEFORE_CHECKS
            (NodeStuffInlinePass. cc))

          (.addCustomPass CustomPassExecutionTime/BEFORE_CHECKS replace-require-pass)

          ;; google SourceMapResolver is broken since it always resolves source map files relative
          ;; from the input name not from its original path. since we always use the resource-name
          ;; as the input it can never find anything. can't use the full path as input as that
          ;; affects the generated alias
          (.setResolveSourceMapAnnotations false)

          (.setWarningLevel DiagnosticGroups/NON_STANDARD_JSDOC CheckLevel/OFF)
          (.setWarningLevel DiagnosticGroups/MISPLACED_TYPE_ANNOTATION CheckLevel/OFF)
          ;; node_modules/@firebase/util/dist/cjs/src/constants.ts:26: ERROR - @define variable  assignment must be global

          ;; unreachable code in react
          (.setWarningLevel DiagnosticGroups/CHECK_USELESS_CODE CheckLevel/OFF)
          (.setNumParallelThreads (get-in state [:compiler-options :closure-threads] 1)))

        ;; :js-provider :shadow uses this for es6 on the classpath
        ;; we want to collect the props only for :shadow not :closure
        _ (when (= :shadow (get-in state [:js-options :js-provider]))
            (.addCustomPass closure-opts CustomPassExecutionTime/AFTER_OPTIMIZATION_LOOP property-collector))

        ;; GCC doesn't seem to track polyfills properly in some cases and just fails to compile
        ;; reinject all polyfills that were added before to limit this problem and just have them
        ;; available if other sources already required them
        force-injected-libs
        (::shadow-js-injected-libs state)

        _ (when (seq force-injected-libs)
            (.setForceLibraryInjection closure-opts force-injected-libs))

        result
        (try
          (util/with-logged-time [state {:type ::shadow-convert
                                         :num-sources (count sources)}]
            (.compile cc
              (-> @default-externs
                  (conj (SourceFile/fromCode
                          "externs-header.shadow.js"
                          "/** @constructor */\nfunction ShadowJS() {};\n"))
                  (conj (load-simplified-externs state)))
              source-files
              closure-opts))
          ;; catch internal closure errors
          (catch Exception e
            (throw (ex-info "failed to convert sources"
                     {:tag ::convert-error
                      :sources (into [] (map :resource-id) sources)}
                     e))))

        _ (throw-errors! state cc result)

        injected-libs
        (get-injected-libs cc)

        source-map
        (.getSourceMap cc)]

    (-> state
        (log-warnings cc result)
        (update ::shadow-js-injected-libs set/union injected-libs)
        (util/reduce->
          (fn [state source-node]
            (.reset source-map)

            (let [name (.getSourceFileName source-node)]
              (cond
                ;; capture polyfills separately later via injected-libs tracking
                (= name polyfill-name)
                state

                :else
                (let [source-file
                      (get source-files-by-name name)

                      {:keys [resource-id ns output-name deps] :as rc}
                      (get resource-by-name name)

                      js
                      (try
                        (ShadowAccess/nodeToJs cc source-map source-node)
                        (catch Exception e
                          (throw (ex-info (format "failed to generate JS for \"%s\"" name) {:name name} e))))

                      sm-json
                      (when generate-source-map?
                        (let [sw (StringWriter.)]
                          ;; for sourcesContent
                          (when (:source-map-include-sources-content co-opts)
                            (.addSourceFile source-map (.getName source-file) (.getCode source-file)))
                          (.appendTo source-map sw output-name)
                          (.toString sw)))

                      deps
                      (data/deps->syms state rc)

                      ;; the :simple optimization may remove conditional requires
                      ;; so we need to walk the code again to find the remaining require calls
                      alive-requires
                      (FindSurvivingRequireCalls/find cc source-node)

                      removed-requires
                      (->> (get require-replacements name)
                           (vals)
                           (remove (fn [dep]
                                     ;; may be symbol, number or string
                                     (or (contains? alive-requires dep)
                                         (contains? alive-requires (str dep)))))
                           (map (fn [dep]
                                  (if (number? dep)
                                    (get-in state [:require-id->sym dep])
                                    dep)))
                           (into #{}))

                      actual-requires
                      (into #{} (remove removed-requires) deps)

                      properties
                      (-> (into #{} (-> property-collector (.-properties) (.get name)))
                          (clean-collected-properties))

                      output
                      (-> {:resource-id resource-id
                           :js js
                           :source (.getCode source-file)
                           :removed-requires removed-requires
                           :actual-requires actual-requires
                           :properties properties
                           :compiled-at (System/currentTimeMillis)}
                          (cond->
                            generate-source-map?
                            (assoc :source-map-json sm-json)))]

                  (assoc-in state [:output resource-id] output)
                  ))))

          (->> (ShadowAccess/getJsRoot cc)
               (.children) ;; the inputs
               )))))

(defmethod build-log/event->str ::closure-cache-read
  [{:keys [num-files]}]
  (format "Closure JS Cache read: %d JS files" num-files))

(defmethod build-log/event->str ::closure-cache-write
  [{:keys [num-files]}]
  (format "Closure JS Cache write: %d JS files" num-files))

(defmethod build-log/event->str ::shadow-cache-read
  [{:keys [num-files]}]
  (format "Shadow JS Cache read: %d JS files" num-files))

(defmethod build-log/event->str ::shadow-cache-write
  [{:keys [num-files]}]
  (format "Shadow JS Cache write: %d JS files" num-files))

(defn convert-sources-simple
  "convert and caches"
  [state sources]
  (let [cache-index-file
        (data/cache-file state "shadow-js" "index.json.transit")

        cache-index
        (or (::shadow-js-cache state)
            ;; read from disk
            (and (.exists cache-index-file)
                 (cache/read-cache cache-index-file))
            ;; ensure that at least the directoy exists so we can write files into it later
            (do (io/make-parents cache-index-file)
                {}))

        cache-options
        (->> cache-affecting-options
             (map #(get-in state %))
             (into []))

        cache-files
        (if (or (not= SHADOW-CACHE-KEY (:SHADOW-CACHE-KEY cache-index))
                (not= cache-options (:CACHE-OPTIONS cache-index)))
          ;; invalidate all cache when the timestamp of this file has changed
          []
          ;; compare each cache key
          (->> (for [{:keys [resource-id cache-key] :as src} sources
                     ;; FIXME: this should probably check if the file exists
                     ;; never should delete individual cached files without also removing the index though
                     :when (= cache-key (get cache-index resource-id))]
                 src)
               ;; need to preserve order for later
               (into [])))

        cache-files-set
        (into #{} (map :resource-id) cache-files)

        recompile-sources
        (->> sources
             (remove #(contains? cache-files-set (:resource-id %)))
             (into []))

        need-compile?
        (boolean (seq recompile-sources))

        state
        (-> state
            (update ::shadow-js-injected-libs set/union (:injected-libs cache-index))
            (cond->
              need-compile?
              (convert-sources-simple* recompile-sources)))

        cache-index-updated
        (if-not need-compile?
          cache-index
          (util/with-logged-time [state {:type ::shadow-cache-write
                                         :num-files (count recompile-sources)}]
            (reduce
              (fn [idx {:keys [cache-key resource-id output-name] :as compiled-src}]
                (let [output
                      (data/get-output! state compiled-src)

                      cache-file
                      (data/cache-file state "shadow-js" output-name)]

                  (cache/write-file cache-file output)

                  (assoc idx resource-id cache-key)))
              (assoc cache-index
                :injected-libs (::shadow-js-injected-libs state)
                :SHADOW-CACHE-KEY SHADOW-CACHE-KEY
                :CACHE-OPTIONS cache-options)
              recompile-sources)))]

    (when need-compile?
      (cache/write-file cache-index-file cache-index-updated))

    (let [state
          (if-not (seq cache-files)
            state
            (util/with-logged-time [state {:type ::shadow-cache-read
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
                  :live-js-deps #{}
                  :dead-js-deps #{}}))]

      (-> state
          (update :js-properties set/union js-properties)
          (assoc ::shadow-js-cache cache-index-updated
                 :dead-js-deps dead-js-deps
                 :live-js-deps live-js-deps))
      )))

(defn goog-compiler-options [state]
  (merge
    {:pretty-print true
     :pseudo-names false
     :language-in :ecmascript-next-in
     :output-feature-set :es6
     :source-map true}
    (get-output-options state)))

(defn convert-goog*
  "convert Closure Library code since it may contain a random mix of ClosureJS and Closure Modules"
  [{:keys [js-options mode] :as state} sources cached-files]
  (let [source-files
        (->> (for [{:keys [resource-name] :as src} sources]
               (let [source (data/get-source-code state src)]
                 (closure-source-file resource-name source)))
             #_(map #(do (println (.getCode %)) %))
             (into [(closure-source-file polyfill-name (output/closure-defines state))]))

        cached-file-names
        (into #{} (map :resource-name) cached-files)

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
        (data/make-closure-compiler)

        co-opts
        (goog-compiler-options state)

        generate-source-map?
        (not (false? (:source-map js-options)))

        closure-opts
        (doto (make-options)
          (set-options co-opts state)
          (.resetWarningsGuard)
          (.setSkipNonTranspilationPasses true)
          (.setRewritePolyfills true)
          (.setNumParallelThreads (get-in state [:compiler-options :closure-threads] 1)))

        ;; _ (.setOptionsForCompilationLevel CompilationLevel/WHITESPACE_ONLY closure-opts)

        result
        (try
          (util/with-logged-time [state {:type ::goog-convert
                                         :num-files (count sources)}]
            (.compile cc @default-externs source-files closure-opts))
          ;; catch internal closure errors
          (catch Exception e
            (throw (ex-info "failed to convert sources"
                     {:tag ::convert-error
                      :sources (into [] (map :resource-id) sources)}
                     e))))

        _ (throw-errors! state cc result)

        source-map
        (.getSourceMap cc)

        injected-libs
        (get-injected-libs cc)]

    (-> state
        (log-warnings cc result)
        ;; FIXME: this is always empty. GCC only tracks polyfills when it actually compiles
        ;; since this used .setSkipNonTranspilationPasses it is always empty
        ;; just hope there aren't any we actually need in the closure lib
        (update ::goog-injected-libs set/union injected-libs)
        (util/reduce->
          (fn [state source-node]
            (.reset source-map)

            (let [name (.getSourceFileName source-node)]
              (cond
                ;; don't touch output of files that are supposed to be cached
                ;; we can't feed partial input to closure
                (contains? cached-file-names name)
                state

                ;; capture polyfills separately later via injected-libs tracking
                (= name polyfill-name)
                state

                ;; file not cached, store in :output
                :else
                (let [source-file
                      (get source-files-by-name name)

                      {:keys [resource-id output-name] :as rc}
                      (get resource-by-name name)

                      js
                      (-> (try
                            (ShadowAccess/nodeToJs cc source-map source-node)
                            (catch Exception e
                              (throw (ex-info (format "failed to generate JS for \"%s\"" name) {:name name} e))))
                          (cond->
                            (= "goog/base.js" name)
                            ;; can't figure out how to make this stay false
                            ;; setMarkAsCompiled is false and is ignored
                            ;; setClosurePass(false) makes other things kaputt
                            (str/replace #"COMPILED = !0" "COMPILED = !1")))

                      sm-json
                      (when generate-source-map?
                        (let [sw (StringWriter.)]
                          ;; for sourcesContent
                          (when-not (false? (get-in state [:compiler-options :source-map-include-sources-content]))
                            (.addSourceFile source-map (.getName source-file) (.getCode source-file)))
                          (.appendTo source-map sw output-name)
                          (.toString sw)))

                      output
                      (-> {:resource-id resource-id
                           :js js
                           :source (.getCode source-file)
                           :compiled-at (System/currentTimeMillis)}
                          (cond->
                            generate-source-map?
                            (assoc :source-map-json sm-json)))]

                  (assoc-in state [:output resource-id] output)
                  ))))

          (->> (ShadowAccess/getJsRoot cc)
               (.children) ;; the inputs
               )))))

(defn convert-goog
  "convert and caches ClosureJS and ClosureModule sources"
  [state sources]
  (let [cache-index-file
        (data/cache-file state "goog-js" "index.json.transit")

        cache-index
        (or (::shadow-js-cache state)
            ;; read from disk
            (and (.exists cache-index-file)
                 (cache/read-cache cache-index-file))
            ;; ensure that at least the directoy exists so we can write files into it later
            (do (io/make-parents cache-index-file)
                {}))

        ;; must invalidate cache if :output-feature-set changes
        cache-options
        (goog-compiler-options state)

        cache-files
        (if (or (not= SHADOW-CACHE-KEY (:SHADOW-CACHE-KEY cache-index))
                (not= cache-options (:CACHE-OPTIONS cache-index)))
          ;; invalidate all cache when the timestamp of this file has changed
          []
          ;; compare each cache key
          (->> (for [{:keys [resource-id cache-key] :as src} sources
                     ;; FIXME: this should probably check if the file exists
                     ;; never should delete individual cached files without also removing the index though
                     :when (= cache-key (get cache-index resource-id))]
                 src)
               ;; need to preserve order for later
               (into [])))

        cache-files-set
        (into #{} (map :resource-id) cache-files)

        recompile-sources
        (->> sources
             (remove #(contains? cache-files-set (:resource-id %)))
             (into []))

        need-compile?
        (boolean (seq recompile-sources))

        ;; can't do a partial compile since goog is picky about missing names
        ;; compile is fast so we can just recompile everything
        state
        (if-not need-compile?
          ;; always restore injected libs so they don't get lost later
          (update state ::goog-injected-libs set/union (:injected-libs cache-index))
          (convert-goog* state (remove :virtual sources) cache-files))

        ;; virtual sources should never be compiled and just be used as is
        state
        (->> sources
             (filter :virtual)
             (reduce
               (fn [state {:keys [resource-id source] :as rc}]
                 (update state :output assoc resource-id {:resource-id resource-id
                                                          :source source
                                                          :js source}))
               state))

        cache-index-updated
        (if-not need-compile?
          cache-index
          (util/with-logged-time [state {:type ::goog-cache-write
                                         :num-files (count recompile-sources)}]
            (reduce
              (fn [idx {:keys [cache-key resource-id output-name] :as compiled-src}]
                (let [output
                      (data/get-output! state compiled-src)

                      cache-file
                      (data/cache-file state "goog-js" output-name)]

                  (cache/write-file cache-file output)

                  (assoc idx resource-id cache-key)))
              (assoc cache-index
                :injected-libs (::goog-injected-libs state)
                :SHADOW-CACHE-KEY SHADOW-CACHE-KEY
                :CACHE-OPTIONS cache-options)
              recompile-sources)))]

    (when need-compile?
      (cache/write-file cache-index-file cache-index-updated))

    ;; restore cached files if there are any and they haven't been restored yet
    (if-not (seq cache-files)
      state
      (util/with-logged-time [state {:type ::goog-cache-read
                                     :num-files (count cache-files)}]
        (reduce
          (fn [state {:keys [resource-id output-name]}]
            (if (get-in state [:output resource-id])
              state
              (let [cache-file
                    (data/cache-file state "goog-js" output-name)

                    cached-output
                    (-> (cache/read-cache cache-file)
                        (assoc :cached true))]

                (assoc-in state [:output resource-id] cached-output)
                )))
          state
          cache-files)))))

;; avoid processing files twice when passing through :advanced
;; so only replace the file paths and let regular optimize do the rest
(defn classpath-js-copy [state sources]
  (reduce
    (fn [state {:keys [resource-id] :as src}]
      (let [source (data/get-source-code state src)
            source (replace-file-references state src source)]
        (update state :output assoc resource-id {:resource-id resource-id
                                                 :source source
                                                 :js source})))
    state
    sources))

(defmethod build-log/event->str ::goog-cache-read
  [{:keys [num-files]}]
  (format "Closure JS Cache read: %d JS files" num-files))

(defmethod build-log/event->str ::goog-cache-write
  [{:keys [num-files]}]
  (format "Closure JS Cache write: %d JS files" num-files))

(defmethod build-log/event->str ::goog-convert
  [{:keys [num-files]}]
  (format "Closure JS convert: %d JS files" num-files))

(defn get-polyfill-infos* []
  (with-open [rdr
              (-> (io/resource "com/google/javascript/jscomp/js/polyfills.txt")
                  (io/reader))]
    (->> (line-seq rdr)
         (map (fn [line]
                (let [[name native-version polyfill-version lib]
                      (str/split line #" ")]
                  (when (seq lib)
                    {:name name
                     :native-version native-version
                     :polyfill-version polyfill-version
                     :lib lib}))))
         (remove nil?)
         (vec))))

(def get-polyfill-infos (memoize get-polyfill-infos*))

(defn get-polyfills
  ([state]
   (get-polyfills state (into ["base"] (map :lib) (get-polyfill-infos))))
  ([state libs]
   (let [cc
         (data/make-closure-compiler)

         co-opts
         (assoc (get-output-options state)
           :optimizations :simple)

         closure-opts
         (doto (make-options)
           (set-options co-opts {})
           (.setRewritePolyfills true)
           (.setEmitUseStrict false)
           (.setForceLibraryInjection libs))

         result
         (.compile cc [] [] closure-opts)]

     (assert (.success result) "didn't expect any errors from polyfill extraction?")

     (.toSource cc))))

(defn make-polyfill-js
  [{::keys
    [goog-injected-libs
     shadow-js-injected-libs
     classpath-js-injected-libs]
    :keys [mode]
    :as state}]

  (let [all
        (if (= :release mode)
          ;; goog and classpath-js will :advanced compiled
          ;; and will compile the polyfills as well which makes
          ;; them unusable for shadow-js which stays simple
          (set/union
            shadow-js-injected-libs
            ;; workaround for https://github.com/google/closure-compiler/issues/3890
            ;; so we can fix it by config and not rely on the automatic injection
            (set (get-in state [:js-options :force-library-injection])))
          (set/union
            (when (contains? #{:es3 :es5} (get-in state [:compiler-options :output-feature-set]))
              ;; cannot figure out how to make closure report these
              ;; when running goog sources through simple it strips away too much stuff we want
              ;; but other modes don't collect polyfill infos
              ;; these are all used by the closure library itself
              #{"es6/array/findindex"
                "es6/util/arrayfromiterator"
                "es6/util/arrayfromiterable"
                "es6/util/makeiterator"
                "es6/string/trimstart"
                "util/checkstringargs"
                "es6/string/startswith"
                "es6/util/createtemplatetagfirstarg"
                "util/defineproperty"
                "base"
                "es6/string/endswith"
                "es6/string/repeat"
                "util/polyfill"
                "es6/util/arrayiterator"
                "util/findinternal"
                "util/defines"
                "util/global"
                "es6/array/find"})
            goog-injected-libs
            shadow-js-injected-libs
            classpath-js-injected-libs
            (set (get-in state [:js-options :force-library-injection]))
            (set (get-in state [:compiler-options :force-library-injection]))))]

    (if (or (not (seq all))
            (= all (:polyfill-libs state)))
      state
      (util/with-logged-time [state {:type ::make-polyfill-js :polyfill-libs all}]
        (assoc state :polyfill-libs all :polyfill-js (get-polyfills state all))))))

(defmethod build-log/event->str ::make-polyfill-js
  [{:keys [polyfill-libs]}]
  (format "Generating %d Polyfills" (count polyfill-libs)))

(comment
  (let [p (get-polyfills {})]
    (println p)
    (count p)
    ))
