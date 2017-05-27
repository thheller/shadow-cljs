(ns shadow.cljs.closure
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [shadow.cljs.util :as util]
            [shadow.cljs.output :as output]
            [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.closure :as cljs-closure]
            [clojure.data.json :as json]
            [cljs.env :as env])
  (:import (java.io StringWriter ByteArrayInputStream FileOutputStream)
           (com.google.javascript.jscomp JSError SourceFile CompilerOptions CustomPassExecutionTime ReplaceCLJSConstants CommandLineRunner VariableMap SourceMapInput DiagnosticGroups CheckLevel JSModule CompilerOptions$LanguageMode SourceMap$LocationMapping BasicErrorManager Result)))

(defn munge-goog-ns [s]
  (-> s
      (str/replace #"_" "-")
      (symbol)))

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

(def default-externs
  (into [] (CommandLineRunner/getDefaultExterns)))

(defn load-externs [{:keys [module-format deps-externs build-modules] :as state}]
  (let [externs
        (distinct
          (concat
            (:externs state)
            (get-in state [:compiler-options :externs])
            (when (= :js module-format)
              ["shadow/cljs/npm_externs.js"])))

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
                   {:keys [name source] :as ext} externs]
               ;; FIXME: use url? deps-path is accurate enough for now
               (SourceFile/fromCode (str "EXTERNS:" deps-path "!/" name) source))
             (into []))

        foreign-externs
        (->> build-modules
             (mapcat :sources)
             (map #(get-in state [:sources %]))
             (filter util/foreign?)
             (filter :externs-source)
             (map (fn [{:keys [source-path js-name externs externs-source] :as foreign-src}]
                    (SourceFile/fromCode (str "EXTERNS:" source-path "!/" js-name) externs-source)))
             ;; just to force the logging
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

(defn read-variable-map [{:keys [cache-dir] :as state} name]
  (let [map-file (io/file cache-dir name)]
    (when (.exists map-file)
      (try
        (VariableMap/load (.getAbsolutePath map-file))
        (catch Exception e
          (prn [:variable-map-load map-file e])
          nil)))))

(defn use-variable-maps? [state]
  (and (not (true? (get-in state [:compiler-options :pseudo-names])))
       (= :advanced (get-in state [:compiler-options :optimizations]))))

(defn read-variable-maps
  [{::keys [compiler compiler-options]
    :keys [cache-dir] :as state}]

  (when (use-variable-maps? state)

    (when-some [data (read-variable-map state "closure.property.map")]
      (.setInputPropertyMap compiler-options data))

    (when-some [data (read-variable-map state "closure.variable.map")]
      (.setInputVariableMap compiler-options data)))

  state)

(defn write-variable-map [{:keys [cache-dir] :as state} name map]
  (when map
    (let [map-file
          (doto (io/file cache-dir name)
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

(defn js-error-xf [{:keys [output-dir] :as state} ^com.google.javascript.jscomp.Compiler cc]
  (comp
    ;; remove some annoying UNDECLARED_VARIABLES in cljs/core.cljs
    ;; these are only used in self-hosted but I don't want to provide externs for them in browser builds
    #_(remove
        (fn [^JSError err]
          (and (= "cljs/core.js" (.-sourceName err))
               (let [text (.-description err)]
                 (or (= "variable process is undeclared" text)
                     (= "variable global is undeclared" text))))))
    (map
      (fn [^JSError err]
        (let [sb
              (StringBuilder.)

              source-name
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
                  (get-in state [:sources src-name])))]

          (doto sb
            (.append (.-description err))
            (.append "\n"))

          (when source-name
            (.append sb "Original: ")

            (when mapping
              (let [{:keys [file url]} src

                    src-loc
                    (or (when file (.getAbsolutePath file))
                        (let [x (str url)]
                          (if-let [idx (str/index-of x ".m2")]
                            (str "~/" (subs x idx))
                            x))
                        (.getOriginalFile mapping))]

                (doto sb
                  (.append src-loc)
                  (.append " [")
                  (.append (.getLineNumber mapping))
                  (.append ":")
                  (.append (.getColumnPosition mapping))
                  (.append "]\n")
                  (.append "Compiled to: "))))

            (doto sb
              (.append source-name)
              (.append "[")
              (.append line)
              (.append ":")
              (.append column)
              (.append "]\n")))

          (-> {:line line
               :column column
               :source-name source-name
               :msg (.toString sb)}
              (cond->
                mapping
                (assoc :original-line (.getLineNumber mapping)
                       :original-column (.getColumnPosition mapping)
                       :original-name (.getOriginalFile mapping)))))))))

;; FIXME: this only works if flush-sources-by-name was called before optimize
;; as it works off the source map file that was generated
;; could work off the :source-map key but that isn't present when using cached files
;; would also need to convert to closure sm format which is then done again on flush
;; I think I can live with flush before optimize for now
(defn add-input-source-maps [state cc]
  (let [{:keys [build-sources output-dir cljs-runtime-path]} state]
    (doseq [src build-sources]
      (let [{:keys [js-name name] :as rc}
            (get-in state [:sources src])

            sm-file
            (io/file output-dir cljs-runtime-path (str js-name ".map"))]

        (when (.exists sm-file)
          ;; not using SourceFile/fromFile as the name that gets displayed in warnings sucks
          ;; public/js/cljs-runtime/cljs/core.cljs vs cljs/core.cljs
          (.addInputSourceMap cc js-name (SourceMapInput. (SourceFile/fromCode name (slurp sm-file))))
          )))))

(defn make-foreign-js-header
  "goog.provide/goog.require statements for foreign js files"
  [{:keys [provides require-order]}]
  (let [sb (StringBuilder.)]
    (doseq [provide provides]
      (doto sb
        (.append "goog.provide(\"")
        (.append (str (comp/munge provide)))
        (.append "\");\n")))
    (doseq [require require-order]
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

  (let [js-mods
        (reduce
          (fn [js-mods {:keys [js-name name depends-on sources] :as mod}]
            (let [js-mod (JSModule. js-name)]
              (when (:default mod)
                (.add js-mod (SourceFile/fromCode "closure_setup.js"
                               (str (output/closure-defines-and-base state)
                                    goog-nodeGlobalRequire-fix))))

              (doseq [{:keys [name type js-name output] :as src}
                      (map #(get-in state [:sources %]) sources)]
                ;; throws hard to track NPE otherwise
                (when-not (and js-name output (seq output))
                  (throw (ex-info "missing output for source" {:js-name js-name :name (:name src)})))

                (case type
                  ;; foreign files only include the goog.require/goog.provide statements
                  ;; not the actual foreign code, that will be prepended after optimizations
                  :foreign
                  (let [content (make-foreign-js-header src)]
                    (.add js-mod (SourceFile/fromCode js-name content)))

                  :js
                  (.add js-mod (SourceFile/fromCode js-name output))

                  :cljs
                  (.add js-mod (SourceFile/fromCode js-name output))

                  (throw (ex-info "unsupported type for closure" src))))

              (doseq [other-mod-name depends-on
                      :let [other-mod (get js-mods other-mod-name)]]
                (when-not other-mod
                  (throw (ex-info "module depends on undefined module" {:mod name :other other-mod-name})))
                (.addDependency js-mod other-mod))

              (assoc js-mods name js-mod)))
          {}
          build-modules)


        modules
        (->> (for [{:keys [name] :as mod} build-modules]
               (assoc mod :js-module (get js-mods name)))
             (into []))]

    (assoc state ::modules modules)))

(defn make-js-module-per-source
  [{:keys [compiler-env build-sources] :as state}]

  (let [base
        (doto (JSModule. "goog.base.js")
          (.add (SourceFile/fromCode "goog.base.js"
                  (str (output/closure-defines-and-base state)
                       goog-nodeGlobalRequire-fix))))

        js-mods
        (reduce
          (fn [js-mods src-name]
            (let [{:keys [ns require-order provides output js-name] :as src}
                  (get-in state [:sources src-name])

                  require-order
                  (->> require-order
                       (remove '#{goog})
                       (map #(get-in state [:provide->source %]))
                       (distinct)
                       (into []))

                  defs
                  (when ns
                    (->> (get-in compiler-env [::ana/namespaces ns :defs])
                         (vals)
                         (filter #(get-in % [:meta :export]))
                         (map :name)
                         (map (fn [def]
                                (let [export-name
                                      (-> def name str comp/munge pr-str)]
                                  (str export-name ":" (comp/munge def)))))
                         (str/join ",")))

                  code
                  (str output
                       ;; module.exports will become window.module.exports, rewritten later
                       (when (seq defs)
                         (str "\nmodule.exports={" defs "};")))

                  js-mod
                  (doto (JSModule. (output/flat-js-name js-name))
                    (.add (SourceFile/fromCode js-name code)))]

              ;; some goog files don't depend on anything
              (when (empty? require-order)
                (.addDependency js-mod base))

              (doseq [require require-order]
                (.addDependency js-mod (get js-mods require)))

              (assoc js-mods src-name js-mod)))
          {}
          build-sources)

        modules
        (->> build-sources
             (map (fn [src-name]
                    (let [{:keys [name js-name require-order] :as src}
                          (get-in state [:sources src-name])]

                      {:name name
                       :js-name (output/flat-js-name js-name)
                       :js-module (get js-mods src-name)
                       :sources [name]})))
             (into [{:name "goog/base.js"
                     :js-name "goog.base.js"
                     :js-module base
                     :sources ["goog/base.js"]}]))]

    (assoc state ::modules modules)))

(defn setup
  [{::keys [modules]
    :keys [module-format closure-configurators compiler-options] :as state}]
  (let [source-map?
        (boolean (:source-map state))

        cc
        (make-closure-compiler (noop-error-manager))

        closure-opts
        (doto (cljs-closure/make-options compiler-options)
          (.resetWarningsGuard)
          (.setWarningLevel DiagnosticGroups/CHECK_TYPES CheckLevel/OFF)
          ;; really only want the undefined variables warnings
          ;; must turn off CHECK_VARIABLES first or it will complain too much (REDECLARED_VARIABLES)
          (.setWarningLevel DiagnosticGroups/CHECK_VARIABLES CheckLevel/OFF)
          ;; this one is very helpful to spot missing externs
          ;; (js/React...) will otherwise just work without externs
          (.setWarningLevel DiagnosticGroups/UNDEFINED_VARIABLES CheckLevel/WARNING))]

    ;; FIXME: make-options already called set-options
    ;; but I want to reset warnings and enable UNDEFINED_VARIABLES
    ;; calling set-options again so user :closure-warnings works
    (cljs-closure/set-options compiler-options closure-opts)

    (when source-map?
      ;; FIXME: required for input source maps
      ;; I do not like flushing here since you do not need the intermediate sources
      ;; when working with an optimized build
      ;; source maps however need them
      (output/flush-sources-by-name state)

      ;; FIXME: path is not used at all but needs to be set
      ;; otherwise the applyInputSourceMaps will have no effect since it happens
      ;; inside a if (sourceMapOutputPath != null)

      (.setSourceMapOutputPath closure-opts "/dev/null")
      (.setApplyInputSourceMaps closure-opts true))

    (.addCustomPass closure-opts CustomPassExecutionTime/BEFORE_CHECKS (ReplaceCLJSConstants. cc))

    ;; (fn [closure-compiler compiler-options state])
    (doseq [cfg closure-configurators]
      (cfg cc closure-opts state))

    (when (= :js module-format)

      ;; cut the goog.exportSymbol call CLJS may have generated
      ;; since they will still export to window which is not what we want
      (set! (.-stripTypePrefixes closure-opts) #{"goog.exportSymbol"})
      ;; can be anything but will be repeated a lot and each extra byte counts
      ;; maybe should chose different symbol since $ is jQuery but who uses that still? :P
      (.setRenamePrefixNamespace closure-opts "$"))

    (when source-map?
      (add-input-source-maps state cc))

    (assoc state
           ::externs (load-externs state)
           ::compiler cc
           ::compiler-options closure-opts)))

(defn rewrite-node-global-access [state js]
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

(defn compile-js-modules
  [{::keys [externs modules compiler compiler-options] :keys [module-format] :as state}]
  (let [js-mods
        (into [] (map :js-module) modules)

        ^Result result
        (.compileModules
          compiler
          externs
          js-mods
          compiler-options)

        success?
        (.success result)

        source-map
        (when (and success? (:source-map state))
          (.getSourceMap compiler))]

    (-> state
        (assoc ::result result)
        (cond->
          success?
          (update ::modules
            (fn [modules]
              (->> modules
                   (map (fn [{:keys [prepend js-name js-module sources] :as x}]
                          ;; must reset source map before calling .toSource
                          (when source-map
                            (.reset source-map)

                            ;; this will add sourcesContent to the source map generated by closure
                            (doseq [src-name sources]
                              (let [input (get-in state [:sources src-name :input])]
                                (.addSourceFile source-map (SourceFile/fromCode src-name @input)))))

                          (let [js (.toSource compiler js-module)]
                            (if-not (seq js)
                              (assoc x :dead true :output "")
                              (-> x
                                  (assoc :output (rewrite-node-global-access state js))
                                  (cond->
                                    source-map
                                    (merge (let [sw (StringWriter.)]

                                             ;; must call .appendTo after calling toSource
                                             (let [lines
                                                   (-> (if (seq prepend)
                                                         (output/line-count prepend)
                                                         0)
                                                       (cond->
                                                         ;; the npm mode will add one additional line
                                                         ;; for the env setup and requires
                                                         (= :js module-format)
                                                         (inc)))]

                                               (.setStartingPosition source-map lines 0))

                                             (.appendTo source-map sw js-name)

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
             (map :js-name)
             (into #{}))

        ;; a module may have contained multiple sources which were all removed
        dead-sources
        (->> modules
             (remove #(contains? dead-modules (:js-name %)))
             (mapcat :sources)
             (into #{}))]

    (assoc state
           ::dead-modules dead-modules
           ::dead-sources dead-sources)))

(defn log-warnings [{::keys [compiler result] :as state}]
  (let [warnings (into [] (js-error-xf state compiler) (.warnings result))]
    (when (seq warnings)
      (util/log state {:type ::warnings
                       :warnings warnings})))

  state)

(defn throw-errors!
  [{::keys [compiler result] :as state}]
  (when-not (.success result)
    (let [errors (into [] (js-error-xf state compiler) (.errors result))]
      (throw (ex-info "closure errors" {:tag ::errors :errors errors}))))

  state)

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
        "var window=global;var $=require(\"./cljs_env\");"]

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
  [{:keys [module-format build-modules] :as state}]
  (when-not (seq build-modules)
    (throw (ex-info "optimize before compile?" {})))

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
          (= :js module-format)
          (-> (strip-dead-modules)
              (module-wrap-npm)))
        (write-variable-maps))))

;; FIXME: about 100ms for even simple files, might be too slow to process each file indiviually
(defn compile-es6 [state {:keys [module-alias name js-name input] :as src}]
  (util/with-logged-time
    [state {:type :compile-es6 :name name}]
    (let [co
          (doto (cljs-closure/make-convert-js-module-options (:compiler-options state))
            (.setSourceMapOutputPath "/dev/null")
            (.setPrettyPrint true) ;; FIXME: only useful for debugging really
            (.setLanguageIn CompilerOptions$LanguageMode/ECMASCRIPT6)
            (.setLanguageOut (cljs-closure/lang-key->lang-mode (get-in state [:compiler-options :language-out] :ecmascript3))))

          ;; FIXME: should really work out how to just convert the single file
          ;; noop since it will warn about missing module files
          ;; this compiles each file individually so the files aren't really missing
          ;; Compiler.moduleLoader is a bit too hidden to get the inputs there
          ;; it only needs the DependecyInfo which we have
          ;; closure will attempt to compile (again) if we pass everything
          cc
          (make-closure-compiler (noop-error-manager))

          src-file
          (SourceFile/fromCode name @input)

          result
          (.compile cc default-externs [src-file] co)]

      (when-not (.success result)
        (throw (ex-info (format "failed to compile %s" name) {:result result})))

      (swap! env/*compiler* update-in [:js-module-index] assoc (str module-alias) name)

      (let [source-map?
            (boolean (:source-map state))

            sm
            (when source-map?
              ;; need to map test/a.js to a.js since we always keep source maps next to the source
              ;; must be done before calling toSource
              (doto (.getSourceMap cc)
                (.setPrefixMappings [(SourceMap$LocationMapping. name (util/file-basename name))])))

            output
            (.toSource cc)]

        (-> src
            (assoc :output output)
            (cond->
              source-map?
              (-> (assoc :source-map-json
                         (let [sw (StringWriter.)]
                           (.appendTo sm sw js-name)
                           (.toString sw)))
                  (update :output str "\n//# sourceMappingURL=" (util/file-basename js-name) ".map\n"))))))))


