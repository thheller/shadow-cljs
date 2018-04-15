(ns shadow.build.compiler
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.tools.reader.reader-types :as readers]
            [clojure.tools.reader :as reader]
            [cljs.analyzer :as cljs-ana]
            [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.spec.alpha :as cljs-spec]
            [cljs.env :as env]
            [cljs.tagged-literals :as tags]
            [cljs.core] ;; do not remove, need to ensure this is loaded before compiling anything
            [shadow.cljs.util :as util]
            [shadow.build.warnings :as warnings]
            [shadow.build.macros :as macros]
            [shadow.build.cache :as cache]
            [shadow.build.cljs-bridge :as cljs-bridge]
            [shadow.build.resource :as rc]
            [shadow.build.ns-form :as ns-form]
            [shadow.build.data :as data]
            [shadow.build.closure :as closure]
            [shadow.build.npm :as npm]
            [clojure.tools.logging :as log]
            [cljs.compiler :as cljs-comp]
            [shadow.build.js-support :as js-support])
  (:import (java.util.concurrent ExecutorService Executors)
           (java.io File StringReader PushbackReader StringWriter)))

(def SHADOW-TIMESTAMP
  ;; timestamp to ensure that new shadow-cljs release always invalidate caches
  ;; technically needs to check all files but given that they'll all be in the
  ;; same jar one is enough
  [(util/resource-last-modified "shadow/build/compiler.clj")
   ;; check a cljs file as well in case the user uses a different cljs version directly
   (util/resource-last-modified "cljs/analyzer.cljc")])

(def ^:dynamic *cljs-warnings-ref* nil)

(defn post-analyze-ns [{:keys [name] :as ast} build-state]
  (let [ast
        (-> ast
            (macros/load-macros)
            (macros/infer-macro-require)
            (macros/infer-macro-use)
            (macros/infer-renames-for-macros))]

    (cljs-bridge/check-uses! ast)
    (cljs-bridge/check-renames! ast)

    (let [ana-info (dissoc ast :env :op :form)]
      (swap! env/*compiler* update-in [::ana/namespaces name] merge ana-info))

    ;; FIXME: cljs.loader is bad, really do not like the implementation
    ;; special case for cljs.loader which requires some consts that aren't actually in the source
    ;; normally cljs.loader is compiled in a special pass AFTER everything else
    ;; we already know everything we need to know but this is still pretty bad
    (when (= name 'cljs.loader)
      (let [{:keys [loader-constants]} build-state]
        (swap! env/*compiler* ana/add-consts loader-constants)))

    ;; FIXME: is this the correct location to do this?
    ;; FIXME: using alter instead of reset, to avoid completely removing meta
    ;; when thing/ns.clj and thing/ns.cljs both have different meta

    (when-let [the-ns (find-ns name)]
      (.alterMeta ^clojure.lang.Namespace the-ns merge (seq [(meta name)])))

    ast))

(defn post-analyze [{:keys [op] :as ast} build-state]
  (case op
    :ns
    (post-analyze-ns ast build-state)
    :ns*
    (throw (ex-info "ns* not supported (require, require-macros, import, import-macros, ... must be part of your ns form)" ast))
    ast))

(defn hijacked-parse-ns [env form rc opts]
  (let [build-state (cljs-bridge/get-build-state)]
    (-> (ns-form/parse form)
        (ns-form/rewrite-ns-aliases build-state)
        (ns-form/rewrite-js-deps build-state)
        (cond->
          (:macros-ns opts)
          (update :name #(symbol (str % "$macros"))))
        (assoc :env env :form form :op :ns))))

;; I don't want to use with-redefs but I also don't want to replace the default
;; keep a reference to the default impl and dispatch based on binding
;; this ensures that out path is only taken when wanted
(defonce default-parse-ns (get-method ana/parse 'ns))

(def ^:dynamic *current-resource* nil)

(defmethod ana/parse 'ns
  [op env form name opts]
  (if *current-resource*
    (hijacked-parse-ns env form *current-resource* opts)
    (default-parse-ns op env form name opts)))

(defn analyze
  ([state compile-state form]
   (analyze state compile-state form false))
  ([state {:keys [ns resource-name] :as compile-state} form repl-context?]
   {:pre [(map? compile-state)
          (symbol? ns)
          (string? resource-name)
          (seq resource-name)]}

   (binding [*ns* (create-ns ns)
             ana/*passes* (:analyzer-passes state)
             ;; [infer-type ns-side-effects] is default, we don't want the side effects
             ;; although it is great that the side effects are now optional
             ;; the default still doesn't handle macros properly
             ;; so we keep hijacking
             ana/*cljs-ns* ns
             ana/*cljs-file* resource-name]

     ;; opts are pretty much 50/50 chance to be used over :options in env/*compiler*
     ;; they are not passed around properly, so make sure it always matches what in compiler env
     ;; initialized in cljs-bridge/ensure-compiler-env
     (let [opts
           (-> (get-in state [:compiler-env :options])
               (cond->
                 (:macros-ns compile-state)
                 (assoc :macros-ns true)))]

       (-> (ana/empty-env) ;; this is anything but empty! requires *cljs-ns*, env/*compiler*
           (cond->
             repl-context?
             (assoc
               :context :expr
               :def-emits-var true))
           ;; ana/analyze rebinds ana/*cljs-warnings* which we already did
           ;; it seems to do this to get rid of duplicated warnings?
           ;; we just do a distinct later
           (ana/analyze* form resource-name opts)
           (post-analyze state))))))

(defn do-compile-cljs-string
  [{:keys [resource-name cljc reader-features] :as init} reduce-fn cljs-source]
  (let [eof-sentinel (Object.)
        opts (merge
               {:eof eof-sentinel}
               (when cljc
                 {:read-cond :allow :features reader-features}))
        in (readers/indexing-push-back-reader (PushbackReader. (StringReader. cljs-source)) 1 resource-name)]

    (binding [comp/*source-map-data*
              (atom {:source-map (sorted-map)
                     :gen-col 0
                     :gen-line 0})]

      (let [result
            (loop [{:keys [ns ns-info] :as compile-state} init]
              (let [ns
                    (if-not (:macros-ns compile-state)
                      ns
                      (-> (str ns)
                          (str/replace #"\$macros" "")
                          (symbol)))

                    form
                    (binding [*ns*
                              (create-ns ns)

                              ana/*cljs-ns*
                              ns

                              ana/*cljs-file*
                              resource-name

                              reader/*data-readers*
                              tags/*cljs-data-readers*

                              reader/*alias-map*
                              (merge reader/*alias-map*
                                (:requires ns-info)
                                (:require-macros ns-info))]
                      (reader/read opts in))]

                (if (identical? form eof-sentinel)
                  ;; eof
                  compile-state
                  (recur (reduce-fn compile-state form)))))]

        (assoc result :source-map (:source-map @comp/*source-map-data*))
        ))))

(defmulti shadow-emit
  (fn [build-state ast]
    (:op ast))
  :default ::default)

(defmethod shadow-emit ::default [_ ast]
  (comp/emit ast))

;; replacing cljs.compiler/emit* :ns cause I honestly have no clue what it is doing
;; most of it seems self-host related which we do not need
;; it also has a hard coded emit for cljs.core which would cause a double emit
;; since deps (correctly) contains cljs.core but not in CLJS
(defmethod shadow-emit :ns [{:keys [mode] :as state} {:keys [name deps] :as ast}]
  ;; FIXME: can't remove goog.require/goog.provide from goog sources easily
  ;; keeping them for CLJS for now although they are not needed in JS mode
  #_(when (= :goog (get-in state [:build-options :module-format])))
  (comp/emitln "goog.provide('" (comp/munge name) "');")

  (when (= name 'cljs.js)
    ;; this fixes the issue that cljs.js unconditionally attempts to load cljs.core$macros
    ;; via goog.require, which should be done when bootstrapping the compiler instead
    ;; this saves downloading a bunch of data prematurely
    (comp/emitln "goog.provide(\"cljs.core$macros\");"))

  (when (= :dev mode)
    (doseq [dep deps
            :when (not= 'goog dep)]

      (let [{:keys [ns type] :as rc} (data/get-source-by-provide state dep)]
        ;; we never need goog.require, just skip emitting it completely
        (when (or (= type :goog)
                  (= type :cljs))
          (comp/emitln "goog.require('" (comp/munge dep) "');"))

        ;; in dev mode each CLJS files shadow.js.require their own js dependencies
        ;; since they might be loaded by the REPL or code-reloading.
        ;; in release mode that will only be done once since the GCC will complain otherwise
        ;; and we don't need to deal with code-reloading or the REPL anyways
        (when (= :shadow-js type)
          (comp/emitln "var " ns "=" (npm/shadow-js-require rc))
          )))))

(defn- progress-bump [x]
  (Math/max x (System/currentTimeMillis)))

(defn default-compile-cljs
  [{:keys [last-progress-ref] :as state} {:keys [ns macros-ns] :as compile-state} form]
  ;; ignore (defmacro ...) in normal cljs compilation since they otherwise end
  ;; up as normal vars and can be called as fns. compile as usual when compiling as macro ns
  ;; FIXME: could use a better warning than "Use of undeclared Var demo.browser/dummy-macro"
  (if (and (not macros-ns)
           (list? form)
           (= 'defmacro (first form)))
    compile-state

    (let [{:keys [op] :as ast}
          (analyze state compile-state form)

          js
          (with-out-str
            (shadow-emit state ast))]

      ;; bump for every compiled form might be overkill
      (swap! last-progress-ref progress-bump)

      (-> compile-state
          (update-in [:js] str js)
          (cond->
            (= op :ns)
            (assoc
              :ns (:name ast)
              :ns-info (dissoc ast :env)))))))

(defn warning-collector [build-env warnings warning-type env extra]
  ;; FIXME: currently there is no way to turn off :infer-externs
  ;; the work is always done and the warning is always generated
  ;; it is just not emitted when *warn-in-infer* is not set

  ;; we collect all warnings always since any warning should prevent caching
  ;; :infer-warnings however are very inaccurate so we filter those unless
  ;; explicitly enabled, mirroring what CLJS does more closely.
  (when (and (or (not= :infer-warning warning-type)
                 (get ana/*cljs-warnings* :infer-warning))
             ;; no-warn macro disables all warnings sometimes
             ;; and we need to respect that, otherwise letfn complains
             ;; since it is allowed to define/reference something out of order
             (not (false? (get ana/*cljs-warnings* warning-type))))

    (let [{:keys [line column]}
          env

          msg
          (ana/error-message warning-type extra)]

      (swap! warnings conj
        {:warning warning-type
         :line line
         :column column
         :msg msg
         :extra extra}
        ))))

(defmacro with-warnings
  "given a body that produces a compilation result, collect all warnings and assoc into :warnings"
  [build-env & body]
  `(let [warnings#
         (atom [])

         result#
         (ana/with-warning-handlers
           [(partial warning-collector ~build-env warnings#)]
           (binding [*cljs-warnings-ref* warnings#]
             ~@body))]

     (assoc result# :warnings @warnings#)))

(defn compile-cljs-string
  [state compile-state cljs-source]
  (with-warnings state
    (do-compile-cljs-string
      compile-state
      (partial default-compile-cljs state)
      cljs-source)))

(defn compile-cljs-seq
  [state compile-state cljs-forms]
  (with-warnings state
    (reduce
      (partial default-compile-cljs state)
      compile-state
      cljs-forms)))

(defn make-runtime-setup
  [state]
  (case (get-in state [:build-options :print-fn])
    :none ""
    ;; default to console
    "cljs.core.enable_console_print_BANG_();\n"))

(defn do-compile-cljs-resource
  [{:keys [compiler-options] :as state}
   {:keys [resource-id resource-name url file output-name] :as rc}
   source]
  (let [{:keys [static-fns elide-asserts fn-invoke-direct infer-externs]}
        compiler-options]

    (binding [ana/*cljs-static-fns*
              (true? static-fns)

              ana/*fn-invoke-direct*
              (true? fn-invoke-direct)

              ana/*file-defs*
              (atom #{})

              ;; initialize with default value
              ;; must set binding to it is thread bound, since the analyzer may set! it
              ana/*unchecked-if*
              ana/*unchecked-if*

              ;; root binding for warnings so (set! *warn-on-infer* true) can work
              ana/*cljs-warnings*
              (-> ana/*cljs-warnings*
                  (cond->
                    (or (and file (= :auto infer-externs))
                        (= :all infer-externs))
                    (assoc :infer-warning true)))

              ana/*unchecked-arrays*
              ana/*unchecked-arrays*

              *assert*
              (not (true? elide-asserts))

              *current-resource*
              rc]

      (util/with-logged-time
        [state {:type :compile-cljs :resource-id resource-id :resource-name resource-name}]

        (let [compile-init
              (-> {:resource-id resource-id
                   :resource-name resource-name
                   :source source
                   :ns 'cljs.user
                   :js ""
                   :cljc (util/is-cljc? resource-name)
                   :reader-features (data/get-reader-features state)}
                  (cond->
                    (:macros-ns rc)
                    (assoc :macros-ns true)))

              {:keys [ns] :as output}
              (cond
                (string? source)
                (compile-cljs-string state compile-init source)

                (vector? source)
                (compile-cljs-seq state compile-init source)

                :else
                (throw (ex-info "invalid cljs source type" {:resource-id resource-id :resource-name resource-name :source source})))]

          (-> output
              (assoc :compiled-at (System/currentTimeMillis))
              (cond->
                (= ns 'cljs.core)
                (update :js str "\n" (make-runtime-setup state))
                )))))))


(defn get-cache-file-for-rc
  ^File [state {:keys [resource-name] :as rc}]
  (data/cache-file state "ana" (str resource-name ".cache.transit.json")))

(defn make-cache-key-map
  "produces a map of {resource-id cache-key} for caching to identify
   whether a cache is safe to use (if any cache-keys do not match if is safer to recompile)"
  [state rc]
  ;; FIXME: would it be enough to just use the immediate deps?
  ;; all seems like overkill but way safer
  (let [;; deps (get-in state [:immediate-deps (:id rc)])
        deps (data/get-deps-for-id state #{} (:resource-id rc))]

    ;; must always invalidate cache on version change
    ;; which will always have a new timestamp
    (-> {:SHADOW-TIMESTAMP SHADOW-TIMESTAMP}
        (util/reduce->
          (fn [cache-map id]
            (assoc cache-map id (get-in state [:sources id :cache-key])))
          deps)
        ;; must also account for macro only changes
        (util/reduce->
          (fn [cache-map {:keys [ns cache-key] :as macro-rc}]
            (assoc cache-map [:macro ns] cache-key))
          (macros/macros-used-by-ids state deps)))))

(def cache-affecting-options
  ;; paths into the build state
  ;; options which may effect the output of the CLJS compiler
  [[:mode]
   [:js-options :js-provider]
   [:compiler-options :source-map]
   [:compiler-options :fn-invoke-direct]
   [:compiler-options :elide-asserts]
   [:compiler-options :reader-features]
   ;; some community macros seem to use this
   ;; hard to track side-effecting macros but even more annoying to run into caching bugs
   ;; so just let any change invalidate everything for safety reasons
   [:compiler-options :tooling-config]
   [:compiler-options :external-config]
   ;; doesn't affect output but affects compiler env
   [:compiler-options :infer-externs]
   ;; these should basically never be changed
   [:compiler-options :static-fns]
   [:compiler-options :optimize-constants]
   [:compiler-options :emit-constants]])

(defn is-cache-blocked? [state {:keys [ns requires macro-requires] :as rc}]
  (let [cache-blockers (get-in state [:build-options :cache-blockers])]
    (or (get-in rc [:ns-info :meta :figwheel-always])
        (get-in rc [:ns-info :meta :dev/always])
        ;; cache-blockers should be a set of namespace symbols or nil
        (when (set? cache-blockers)
          ;; block if the ns itself is blocked or when it requires blocked namespaces directly
          (or (contains? cache-blockers ns)
              (some cache-blockers requires)
              (some cache-blockers macro-requires))))))

(defn load-cached-cljs-resource
  [{:keys [build-options] :as state}
   {:keys [ns output-name resource-id resource-name] :as rc}]
  (let [{:keys [cljs-runtime-path]} build-options
        cache-file (get-cache-file-for-rc state rc)]

    (try
      (when (.exists cache-file)

        (let [cache-data
              (cache/read-cache cache-file)

              cache-key-map
              (make-cache-key-map state rc)]

          ;; just checking the "maximum" last-modified of all dependencies is not enough
          ;; must check times of all deps, mostly to guard against jar changes
          ;; lib-A v1 was released 3 days ago
          ;; lib-A v2 was released 1 day ago
          ;; we depend on lib-A and compile against v1 today
          ;; realize that a new version exists and update deps
          ;; compile again .. since we were compiled today the min-age is today
          ;; which is larger than v2 release date thereby using cache if only checking one timestamp

          (when (and (= cache-key-map (:cache-keys cache-data))
                     (every?
                       #(= (get-in state %)
                          (get-in cache-data [:compiler-options %]))
                       cache-affecting-options))

            (util/log state {:type :cache-read :resource-id resource-id :resource-name resource-name})

            ;; restore analysis data
            (let [ana-data (:analyzer cache-data)]
              (swap! env/*compiler* update-in [::ana/namespaces (:ns cache-data)] merge ana-data)
              (macros/load-macros ana-data))

            ;; restore specs
            (let [{:keys [ns-specs ns-spec-vars]} cache-data]
              (swap! cljs-spec/registry-ref merge ns-specs)

              ;; no idea why this is named so weirdly and private
              (let [priv-var (find-var 'cljs.spec.alpha/_speced_vars)]
                (swap! @priv-var set/union ns-spec-vars)))

            (assoc (:output cache-data) :cached true))))

      (catch Exception e
        (util/warn state {:type :cache-error
                          :action :read
                          :ns ns
                          :id resource-id
                          :error e})
        nil))))

(defn write-cached-cljs-resource
  [state
   {:keys [ns requires resource-id resource-name] :as rc}
   {:keys [warnings] :as output}]
  {:pre [(rc/valid-output? output)]}

  (cond
    ;; don't cache files with warnings
    (seq warnings)
    (do (util/log state {:type :cache-skip
                         :ns ns
                         :id resource-id})
        nil)

    :else
    (let [cache-file (get-cache-file-for-rc state rc)]
      (try
        (let [cache-compiler-options
              (reduce
                (fn [cache-options option-path]
                  (assoc cache-options option-path (get-in state option-path)))
                {}
                cache-affecting-options)

              ns-str
              (str ns)

              spec-filter-fn
              (fn [v]
                (or (= ns-str (namespace v))
                    (= ns (-> v meta :fdef-ns))))

              ns-specs
              (reduce-kv
                (fn [m k v]
                  (if-not (spec-filter-fn k)
                    m
                    (assoc m k v)))
                {}
                ;; this is {spec-kw|sym raw-spec-form}
                @cljs-spec/registry-ref)

              ;; this is a #{fqn-var-sym ...}
              ns-speced-vars
              (->> (cljs-spec/speced-vars)
                   (filter spec-filter-fn)
                   (into []))

              ana-data
              (get-in @env/*compiler* [::ana/namespaces ns])

              cache-data
              {:output output
               :cache-keys (make-cache-key-map state rc)
               :analyzer ana-data
               :ns ns
               :ns-specs ns-specs
               :ns-speced-vars ns-speced-vars
               :compiler-options cache-compiler-options}]

          (io/make-parents cache-file)
          (cache/write-file cache-file cache-data)

          (util/log state {:type :cache-write :resource-id resource-id :resource-name resource-name :ns ns})
          true)
        (catch Exception e
          (util/warn state {:type :cache-error
                            :action :write
                            :ns ns
                            :id resource-id
                            :error e})
          nil)
        ))))

(defn maybe-compile-cljs
  "take current state and cljs resource to compile
   make sure you are in with-compiler-env"
  [{:keys [build-options] :as state} {:keys [resource-id resource-name from-jar file url] :as rc}]
  (let [{:keys [cache-level]}
        build-options

        cache?
        (and (not= resource-name "cljs/loader.cljs") ;; can't be cached due to consts injection
             (or (and (= cache-level :all)
                      ;; don't cache files with no actual backing url/file
                      (or url file))
                 (and (= cache-level :jars)
                      from-jar))
             (not (is-cache-blocked? state rc)))]

    (or (when cache?
          (load-cached-cljs-resource state rc))
        (let [source
              (data/get-source-code state rc)

              output
              (try
                (do-compile-cljs-resource state rc source)
                (catch Exception e
                  (let [{:keys [tag line] :as data}
                        (ex-data e)

                        column
                        (or (:column data) ;; cljs.analyzer
                            (:col data)) ;; tools.reader

                        err-data
                        (-> {:tag ::compile-cljs
                             :source-id resource-id
                             :url url
                             :file file}
                            (cond->
                              line
                              (assoc :line line)

                              column
                              (assoc :column column)

                              (and data line column)
                              (assoc :source-excerpt
                                     (warnings/get-source-excerpt
                                       ;; FIXME: this is a bit ugly but compilation failed so the source is not in state
                                       ;; but the warnings extractor wants to access it
                                       (assoc-in state [:output resource-id] {:source source})
                                       rc
                                       {:line line :column column}))))]

                    (throw (ex-info (format "failed to compile resource: %s" resource-id) err-data e)))))]

          (when cache?
            (write-cached-cljs-resource state rc output))

          ;; fresh compiled, not from cache
          (assoc output :cached false)))))

(defn generate-output-for-source
  [state {:keys [resource-id type] :as src}]
  {:pre [(rc/valid-resource? src)]
   :post [(rc/valid-output? %)]}

  (let [output (get-in state [:output resource-id])]
    ;; skip compilation if output is already present from previous compile
    ;; always recompile files with warnings
    (if (and output (not (seq (:warnings output))))
      output
      (maybe-compile-cljs state src)
      )))

(defn par-compile-one
  [{:keys [last-progress-ref] :as state}
   ready-ref
   errors-ref
   {:keys [resource-id type requires provides] :as src}]
  (assert (= :cljs type))
  (assert (set? requires))
  (assert (set? provides))

  (loop [idle-count 1]
    (let [ready @ready-ref]
      (cond
        ;; skip work if errors occured
        (seq @errors-ref)
        src

        ;; only compile once all dependencies are compiled
        ;; FIXME: sleep is not great, cljs.core takes a couple of sec to compile
        ;; this will spin a couple hundred times, doing additional work
        ;; don't increase the sleep time since many files compile in the 5-10 range
        (not (set/superset? ready requires))
        (do (Thread/sleep 5)

            ;; forcefully abort compilation when no progress was made for a long time
            ;; progress is measured by bumping last-progress-ref in default-compile-cljs
            ;; otherwise the compilation runs forever with no way to abort in case of bugs
            (when (> (- (System/currentTimeMillis) @last-progress-ref)
                     (get-in state [:build-options :par-timeout] 60000))
              (let [pending (set/difference requires ready)]

                (swap! errors-ref assoc resource-id
                  (ex-info (format "aborted par-compile, %s still waiting for %s"
                             resource-id
                             pending)
                    {:aborted resource-id
                     :pending pending}))))

            (recur (inc idle-count)))

        :ready-to-compile
        (try
          (let [output (generate-output-for-source state src)

                ;; FIXME: this does not seem ideal
                ;; maybe generate-output-for-source should expose the actual provides it generated

                ;; we need to mark aliases as ready as soon as a resource is ready
                ;; cljs.spec.alpha also provides clojure.spec.alpha only
                ;; if someone used the alias since that happened at resolve time
                ;; the resource itself does not provide the alias
                provides
                (reduce
                  (fn [provides provide]
                    (if-let [alias (get-in state [:ns-aliases-reverse provide])]
                      (conj provides alias)
                      provides))
                  provides
                  provides)]

            (swap! ready-ref set/union provides)
            output)

          (catch Throwable e ;; asserts not covered by Exception
            (swap! errors-ref assoc resource-id e)
            src
            ))))))

(defn load-core []
  ;; cljs.core is already required and loaded when this is called
  ;; this just interns the macros into the analyzer env
  (cljs-ana/intern-macros 'cljs.core))

(defn par-compile-cljs-sources
  "compile files in parallel, files MUST be in dependency order and ALL dependencies must be present
   this cannot do a partial incremental compile"
  [{:keys [executor] :as state} sources non-cljs-provides]
  {:pre [(set? non-cljs-provides)
         (every? symbol non-cljs-provides)]}

  (cljs-bridge/with-compiler-env state
    (load-core)
    (let [;; namespaces that we don't need to wait for
          ready
          (atom non-cljs-provides)

          ;; source-id -> exception
          errors
          (atom {})

          task-results
          (->> (for [src sources]
                 ;; bound-fn for with-compiler-state
                 (let [task-fn (bound-fn [] (par-compile-one state ready errors src))]
                   ;; things go WTF without the type tags, tasks will return nil
                   (.submit ^ExecutorService executor ^Callable task-fn)))
               (doall) ;; force submit all, then deref
               (into [] (map deref)))]

      ;; unlikely to encounter 2 concurrent errors
      ;; so unpack for a single error for better stacktrace
      (let [errs @errors]
        (case (count errs)
          0 nil
          1 (let [[_ err] (first errs)]
              (throw err))
          (throw (ex-info "compilation failed" errs))))

      (reduce
        (fn [state {:keys [resource-id] :as output}]
          (when (nil? output)
            (throw (ex-info "a compile task returned nil?" {})))
          (assert resource-id)
          (update state :output assoc resource-id output))
        state
        task-results)
      )))

(defn seq-compile-cljs-sources
  "compiles with just the main thread, can do partial compiles assuming deps are compiled"
  [state sources]
  (cljs-bridge/with-compiler-env state
    (load-core)
    (reduce
      (fn [state {:keys [resource-id type] :as src}]
        (assert (= :cljs type))
        (let [output (generate-output-for-source state src)]
          (assoc-in state [:output resource-id] output)))
      state
      sources)))

(defn use-extern-properties [{:keys [js-properties] :as state}]
  ;; this is used by cljs-hacks/infer-externs-dot and should contains all known properties from externs (strings)
  (assoc-in state [:compiler-env :shadow/js-properties]
    (set/union
      js-properties ;; from JS deps
      (::closure/extern-properties state) ;; from CC externs
      )))

(defn compile-cljs-sources [{:keys [executor] :as state} sources non-cljs-provides]
  (-> state
      (closure/load-extern-properties)
      (use-extern-properties)
      (cond->
        executor
        (par-compile-cljs-sources sources non-cljs-provides)

        ;; seq compile doesn't really need the provides since it doesn't need to coordinate threads
        (not executor)
        (seq-compile-cljs-sources sources))))

(defn copy-source-to-output [state sources]
  (reduce
    (fn [state {:keys [resource-id] :as src}]
      (let [source (data/get-source-code state src)]
        (update state :output assoc resource-id {:resource-id resource-id
                                                 :source source
                                                 :js source})))
    state
    sources))

(defn maybe-closure-convert [{:keys [output] :as state} npm convert-fn]
  ;; incremental compiles might not need recompiling
  ;; if reset removed one output we must recompile everything again
  ;; this could probably do some more sophisticated caching
  ;; but for now closure is fast enough to do it all over again
  (if (every? #(contains? output %) (map :resource-id npm))
    state
    (convert-fn state npm)))

(defn remove-dead-js-deps [{:keys [build-sources dead-js-deps] :as state}]
  (let [remove-fn
        (fn [sources]
          (->> sources
               (remove (fn [src-id]
                         (let [{:keys [ns]} (data/get-source-by-id state src-id)]
                           (contains? dead-js-deps ns))))
               (into [])))]

    (-> state
        (update :build-sources remove-fn)
        (update :build-modules (fn [mods]
                                 (->> mods
                                      (map #(update % :sources remove-fn))
                                      (into [])))))))

;; when requiring a JS dependency the chosen ns symbol might change because it is based on the filename
;; but cache would still have the old name so we need to ensure that a changed dep will also
;; invalidate the cache

;; For CLJS this was already ensured but the caching for JS is much less strict so it wouldn't recompiled
;; when a custom :resolve config was used. doing this here now so it works for every source

;;   import ... from \"react\"
;; would result in something like
;;   module$node_modules$react$index
;; being chosen as the variable name
;;   :resolve {\"react\" {:target :npm :require \"preact\"}}
;; would result in
;;   module$node_modules$preact$dist$index
;; but the cache wouldn't invalidate because the file itself didn't change

(defn ensure-cache-invalidation-on-resolve-changes
  "populates :cache-key of the resource with the resolved symbols of its deps
   to ensure recompilation when their names change"
  [state resource-id]
  (let [rc (data/get-source-by-id state resource-id)
        deps-syms (data/deps->syms state rc)]
    (update-in state [:sources resource-id :cache-key] into deps-syms)))

(defn compile-all
  ([{:keys [build-sources] :as state}]
   (compile-all state build-sources))
  ([{:keys [executor last-progress-ref] :as state} source-ids]
   " compile a list of sources by id,
requires that the ids are in dependency order
requires that ALL of the dependencies NOT listed are already compiled
eg. you cannot just compile clojure.string as it requires other files to be compiled first "
   (let [js-provider
         (get-in state [:js-options :js-provider])

         state
         (reduce ensure-cache-invalidation-on-resolve-changes state source-ids)

         sources
         (into [] (map #(data/get-source-by-id state %)) source-ids)

         {:keys [cljs goog js shadow-js] :as sources-by-type}
         (group-by :type sources)

         ;; par compile needs to know which names are going to be provided
         ;; since they are no longer compiled interleaved but in separate phases
         ;; we need to sort them out first, but the names are all we need
         ;; since they don't have analyzer data anyways
         non-cljs-provides
         (->> sources
              (remove #(= :cljs (:type %)))
              (map :provides)
              (reduce set/union #{})
              (set/union (:magic-syms state)))

         optimizing?
         (let [x (get-in state [:compiler-options :optimizations])]
           (or (nil? x)
               (not= x :none)))]

     ;; bump when starting a compile so watch doesn't cause timeouts
     (swap! last-progress-ref progress-bump)

     (-> state
         (assoc :compile-start (System/currentTimeMillis))
         ;; order of this is important
         ;; CLJS first since all it needs are the provided names
         (cond->
           (seq goog)
           (copy-source-to-output goog)

           ;; FIXME: removed foreign support
           ;; (seq foreign)
           ;; (copy-source-to-output foreign)

           ;; FIXME: tbd ... I'm not sure I want to do css
           ;; (seq css)
           ;; (compile-css-sources css)

           ;; FIXME: figure out if its always safe to pass processed files
           ;; into optimizations or whether that prefers the actual sources to do
           ;; the conversions while optimizing. conversion may lose information
           ;; the optimizer may need. it does preserve type annotations but not much else

           ;; only convert for :none?
           #_(and (not optimizing?) (seq npm))
           (seq js)
           (maybe-closure-convert js closure/convert-sources)

           (seq shadow-js)
           (maybe-closure-convert shadow-js closure/convert-sources-simple)

           ;; do this last as it uses data from above
           (seq cljs)
           (compile-cljs-sources cljs non-cljs-provides)
           ;; optimize the unprocessed sources
           ;; since processing may have lose information
           #_(and optimizing? (seq npm))
           #_(copy-source-to-output npm))


         (remove-dead-js-deps)
         (assoc :compile-finish (System/currentTimeMillis))
         ))))



