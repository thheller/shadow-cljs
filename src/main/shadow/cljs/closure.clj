(ns shadow.cljs.closure
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [shadow.cljs.util :as util]
            [shadow.cljs.output :as output]
            [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.closure :as closure]
            [clojure.data.json :as json]
            [cljs.env :as env])
  (:import (java.io StringWriter ByteArrayInputStream FileOutputStream)
           (com.google.javascript.jscomp JSError SourceFile CompilerOptions CustomPassExecutionTime ReplaceCLJSConstants CommandLineRunner VariableMap SourceMapInput DiagnosticGroups CheckLevel JSModule CompilerOptions$LanguageMode SourceMap$LocationMapping BasicErrorManager)))

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

(defn load-externs [{:keys [externs deps-externs build-modules] :as state}]
  (let [externs
        (distinct
          (concat
            (:externs state)
            (get-in state [:compiler-options :externs])))

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

;; added by default in init-state
(defn closure-add-replace-constants-pass [cc ^CompilerOptions co state]
  (.addCustomPass co CustomPassExecutionTime/BEFORE_CHECKS (ReplaceCLJSConstants. cc)))

(defn closure-register-cljs-protocol-properties
  "this is needed to make :check-types work

   It registers all known CLJS protocols with the Closure TypeRegistry
   each method is as a property on Object since most of the time Closure doesn't know the proper type
   and annotating everything seems unlikely.

   The property on Object doesn't hurt and won't hinder renaming (as opposed to externs)"
  [cc co {:keys [compiler-env build-sources] :as state}]
  (when (contains? #{:warning :error} (get-in state [:compiler-options :closure-warnings :check-types]))
    (let [type-reg
          (.getTypeRegistry cc)

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
              )))))))

(defn read-variable-map [{:keys [cache-dir] :as state} name]
  (let [map-file (io/file cache-dir name)]
    (when (.exists map-file)
      (try
        (VariableMap/load (.getAbsolutePath map-file))
        (catch Exception e
          (prn [:variable-map-load map-file e])
          nil)))))

(defn write-variable-map [{:keys [cache-dir] :as state} name map]
  (when map
    (let [map-file
          (doto (io/file cache-dir name)
            (io/make-parents))

          bytes
          (ByteArrayInputStream. (.toBytes map))]

      (with-open [out (FileOutputStream. map-file)]
        (io/copy bytes out)))))

(defn closure-add-variable-maps [cc co {:keys [cache-dir] :as state}]
  (when-some [data (read-variable-map state "closure.property.map")]
    (.setInputPropertyMap co data))

  (when-some [data (read-variable-map state "closure.variable.map")]
    (.setInputVariableMap co data)))

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
            (.append "]\n"))

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

(defn foreign-js-source-for-mod [state {:keys [sources] :as mod}]
  (->> sources
       (map #(get-in state [:sources %]))
       (filter util/foreign?)
       (map :output)
       (str/join "\n")))

;; CLOSURE-WARNING: Property nodeGlobalRequire never defined on goog
;; Original: ~/.m2/repository/org/clojure/clojurescript/1.9.542/clojurescript-1.9.542.jar!/cljs/core.cljs [293:4]
;; cljs/core.cljs contains a call to goog.nodeGlobalRequire(...) which is only used in self-host mode
;; but causes the closure compiler --check to complain
;; since we are never going to use it just emit a noop to get rid of the warning

(def goog-nodeGlobalRequire-fix
  "\ngoog.nodeGlobalRequire = function(path) { return false };\n")

(defn make-closure-modules
  "make a list of modules (already in dependency order) and create the closure JSModules"
  [state modules]

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
          modules)]
    (for [{:keys [name] :as mod} modules]
      (assoc mod :js-module (get js-mods name))
      )))

(defn closure-setup
  [{:keys [build-modules closure-configurators compiler-options] :as state}]
  (let [modules
        (make-closure-modules state build-modules)

        source-map?
        (boolean (:source-map state))

        cc
        (make-closure-compiler (noop-error-manager))

        closure-opts
        (doto (closure/make-options compiler-options)
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
    (closure/set-options compiler-options closure-opts)

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

    ;; (fn [closure-compiler compiler-options state])
    (doseq [cfg closure-configurators]
      (cfg cc closure-opts state))

    (when source-map?
      (add-input-source-maps state cc))

    (assoc state :closure {:externs (load-externs state)
                           :modules modules
                           :compiler cc
                           :compiler-options closure-opts})))

(defn closure-compile [{:keys [closure] :as state}]
  (let [{:keys [externs modules compiler compiler-options]}
        closure

        result
        (.compileModules
          compiler
          externs
          (into [] (map :js-module) modules)
          compiler-options)]

    (update state :closure assoc :result result)))

(defn closure-warnings [{:keys [closure] :as state}]
  (let [{:keys [compiler result]} closure]
    (let [warnings (into [] (js-error-xf state compiler) (.warnings result))]
      (when (seq warnings)
        (util/log state {:type ::warnings
                         :warnings warnings}))))

  state)

(defn closure-errors! [{:keys [closure] :as state}]
  (let [{:keys [compiler result]} closure]
    (when-not (.success result)
      (let [errors (into [] (js-error-xf state compiler) (.errors result))]
        (throw (ex-info "closure errors" {:tag ::errors :errors errors})))))

  state)

(defn- set-check-only
  [{:keys [closure] :as state}]
  (let [{:keys [compiler-options]} closure]
    (.setChecksOnly compiler-options true))

  state)

(defn closure-check
  [state]
  (util/with-logged-time
    [state {:type :closure-check}]
    (-> state
        (closure-setup)
        (set-check-only)
        (closure-compile)
        (closure-warnings)
        (closure-errors!))))

(defn closure-optimize
  "takes the current defined modules and runs it through the closure optimizer

   will return the state with :optimized a list of module which now have a js-source and optionally source maps"
  ([state optimizations]
   (-> state
       (update :compiler-options assoc :optimizations optimizations)
       (closure-optimize)))
  ([{:keys [build-modules closure-configurators bundle-foreign cljs-runtime-path] :as state}]
   (when-not (seq build-modules)
     (throw (ex-info "optimize before compile?" {})))

   (util/with-logged-time
     [state {:type :closure-optimize}]

     (let [source-map?
           (boolean (:source-map state))

           {:keys [closure] :as state}
           (-> state
               (closure-setup)
               (closure-compile)
               (closure-warnings)
               (closure-errors!))

           {:keys [result compiler modules]}
           closure]

       (let [source-map
             (when source-map? (.getSourceMap compiler))

             optimized-modules
             (->> modules
                  (mapv
                    (fn [{:keys [js-name js-module prepend append default] :as mod}]
                      (when source-map
                        (.reset source-map))
                      (let [output
                            (.toSource compiler js-module)

                            module-prefix
                            (cond
                              default
                              (:unoptimizable state)

                              (:web-worker mod)
                              (let [deps (:depends-on mod)]
                                (str (str/join "\n" (for [other modules
                                                          :when (contains? deps (:name other))]
                                                      (str "importScripts('" (:js-name other) "');")))
                                     "\n\n"))

                              :else
                              "")

                            module-prefix
                            (if (= :inline bundle-foreign)
                              (str prepend (foreign-js-source-for-mod state mod) module-prefix)
                              (str prepend "\n" module-prefix))

                            module-prefix
                            (if (seq module-prefix)
                              (str module-prefix "\n")
                              "")

                            source-map-name
                            (str js-name ".map")

                            final-output
                            (str module-prefix
                                 output
                                 append
                                 (when source-map
                                   (str "\n//# sourceMappingURL=" cljs-runtime-path "/" source-map-name "\n")))]

                        (-> mod
                            (dissoc :js-module)
                            (assoc :output final-output)
                            (cond->
                              source-map
                              (merge (let [sw
                                           (StringWriter.)

                                           _ (.setWrapperPrefix source-map module-prefix)
                                           _ (.appendTo source-map sw js-name)

                                           closure-json
                                           (.toString sw)]

                                       {:source-map-json closure-json
                                        :source-map-name source-map-name}))))))))]

         ;; see closure-add-variable-maps configurator which loads these before compiling
         (write-variable-map state "closure.variable.map" (.-variableMap result))
         (write-variable-map state "closure.property.map" (.-propertyMap result))

         (assoc state :optimized optimized-modules))

       ))))

;; FIXME: about 100ms for even simple files, might be too slow to process each file indiviually
(defn compile-es6 [state {:keys [module-alias name js-name input] :as src}]
  (util/with-logged-time
    [state {:type :compile-es6 :name name}]
    (let [co
          (doto (closure/make-convert-js-module-options (:compiler-options state))
            (.setSourceMapOutputPath "/dev/null")
            (.setPrettyPrint true) ;; FIXME: only useful for debugging really
            (.setLanguageIn CompilerOptions$LanguageMode/ECMASCRIPT6)
            (.setLanguageOut (closure/lang-key->lang-mode (get-in state [:compiler-options :language-out] :ecmascript3))))

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


