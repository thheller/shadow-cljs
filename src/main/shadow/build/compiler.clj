(ns shadow.build.compiler
  (:require
    [clojure.spec.alpha :as s]
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
    [cljs.source-map :as sm]
    [shadow.debug :refer (?> ?-> ?->>)]
    [shadow.lazy :as lazy]
    [shadow.cljs.util :as util]
    [shadow.build.warnings :as warnings]
    [shadow.build.macros :as macros]
    [shadow.build.cache :as cache]
    [shadow.build.cljs-hacks :as cljs-hacks]
    [shadow.build.cljs-bridge :as cljs-bridge]
    [shadow.build.resource :as rc]
    [shadow.build.ns-form :as ns-form]
    [shadow.build.data :as data]
    [shadow.build.closure :as closure]
    [shadow.build.npm :as npm]
    [shadow.jvm-log :as log]
    [shadow.build.async :as async]
    [shadow.debug :as dbg])
  (:import (java.util.concurrent ExecutorService)
           (java.io File StringReader PushbackReader StringWriter)
           [java.util.concurrent.atomic AtomicLong]))

(def SHADOW-TIMESTAMP
  ;; timestamp to ensure that new shadow-cljs release always invalidate caches
  ;; technically needs to check all files but given that they'll all be in the
  ;; same jar one is enough
  [(util/resource-last-modified "shadow/build/compiler.clj")
   (util/resource-last-modified "shadow/build/cljs_hacks.cljc")
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

    ;; no longer need the special case handling for cljs.loader since it is just aliased to shadow.loader
    #_(when (= name 'cljs.loader)
        (let [{:keys [loader-constants]} build-state]
          (swap! env/*compiler* ana/add-consts loader-constants)))

    ;; FIXME: is this the correct location to do this?
    ;; FIXME: using alter instead of reset, to avoid completely removing meta
    ;; when thing/ns.clj and thing/ns.cljs both have different meta

    (when-let [the-ns (find-ns name)]

      (let [{::keys [cljs-keys] :as ns-meta}
            (meta the-ns)

            new-meta
            (meta name)

            ;; remember and remove keys that the CLJS version of the ns added
            ;; to ensure that live-update does not retain deleted metadata
            ;; but does not remove metadata the CLJ version might have set
            new-meta
            (-> (apply dissoc ns-meta cljs-keys)
                (merge new-meta)
                (assoc ::cljs-keys (into #{} (keys new-meta))))]

        (.resetMeta ^clojure.lang.Namespace the-ns new-meta)))

    ast))

(defn post-analyze [{:keys [op] :as ast} build-state]
  (case op
    :ns
    (post-analyze-ns ast build-state)
    :ns*
    (throw (ex-info "ns* not supported (require, require-macros, import, import-macros, ... must be part of your ns form)" ast))
    ast))

(defmethod ana/error-message ::unexpected-name
  [warning-type {:keys [resource-name expected-name] :as info}]
  (format "Invalid Filename, got %s but expected %s (or .cljc)" resource-name expected-name))

(defn hijacked-parse-ns [env form rc opts]
  (let [build-state (cljs-bridge/get-build-state)]
    (when (:unexpected-name rc)
      (let [ns-sym-meta (-> form second meta)]
        (ana/warning
          ::unexpected-name
          ;; want the warning to highlight the symbol not the ns-form
          (merge env (select-keys ns-sym-meta [:line :column :end-line :end-column]))
          (select-keys rc [:resource-name :expected-name :file :url]))))

    (-> (ns-form/parse form)
        (ns-form/rewrite-ns-aliases build-state)
        (ns-form/rewrite-js-deps build-state)
        (cond->
          (:macros-ns opts)
          (update :name #(symbol (str % "$macros"))))
        (assoc :env env :form form :op :ns))))

;; private in cljs.core
(defn protocol-prefix [psym]
  (str (-> (str psym)
           (.replace \. \$)
           (.replace \/ \$))
       "$"))

(defn find-protocols-pass [env ast opts]
  (when (= :def (:op ast))
    ;; (def sym ...)
    ;; sym will have a :protocol some.ns/sym metadata
    ;; if defined by defprotocol
    ;; the ast otherwise doesn't keep a hint since its all
    ;; in the analyzer data
    (let [def-sym (-> ast :form second)
          {:keys [protocol] :as sym-meta} (meta def-sym)]
      (when (symbol? protocol)
        (let [protocol-prop def-sym
              protocol-ns (-> env :ns :name)
              pprefix (protocol-prefix protocol)]

          ;; assoc into ns so cache can restore it
          (swap! env/*compiler* update-in [::cljs-ana/namespaces protocol-ns :shadow/protocol-prefixes] util/set-conj pprefix)
          ;; used by externs inference since it otherwise can't identify protocol properties
          (swap! env/*compiler* update :shadow/protocol-prefixes util/set-conj pprefix)
          ))))
  ast)

(defn find-js-require-pass [env ast opts]
  (when (and (= :invoke (:op ast))
             ;; js/require called at the REPL should not be added to metadata since it becomes sticky
             (not (::repl-context env)))
    (let [{:keys [form]} ast]
      (when (and (seq? form)
                 (= 'js/require (first form))
                 (= 2 (count form)))
        (let [require (second form)
              ns (-> env :ns :name)]
          (when (string? require)
            (swap! env/*compiler* update-in [::cljs-ana/namespaces ns :shadow/js-requires] util/set-conj require)
            )))))
  ast)

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

(def ana-js-globals
  (into {}
    (map #(vector % {:op :js-var :name % :ns 'js})
      '(alert window document console escape unescape
         screen location navigator history location
         global process require module exports))))

(defn empty-env
  "Construct an empty analysis environment. Required to analyze forms."
  [state ns]
  {:ns (ana/get-namespace ns)
   :shadow.build/mode (:shadow.build/mode state)
   :shadow.build/tweaks (true? (get-in state [:compiler-options :shadow-tweaks]))
   :context :statement
   :locals {}
   :fn-scope []
   ;; FIXME: write a patch that gets rid of this. totally pointless to have these in each env
   :js-globals ana-js-globals})

(def ^:dynamic *analyze-top* nil)

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
                 (assoc :macros-ns true)))

           injected-forms-ref
           (atom [])

           ;; this is anything but empty! requires *cljs-ns*, env/*compiler*
           base-env
           (-> (empty-env state ns)
               (cond->
                 repl-context?
                 (assoc ::repl-context true
                        :context :expr
                        :def-emits-var true)))


           result
           (binding [*analyze-top*
                     (fn [form]
                       ;; need to clear potential loop bindings, dunno why it doesn't use env to track these ...
                       (binding [ana/*recur-frames* nil
                                 ana/*loop-lets* ()]
                         ;; will be turned into statements. repl-context would turn them into :expr
                         (let [ast (analyze state compile-state form false)]
                           (swap! injected-forms-ref conj ast))))]
             (-> base-env
                 ;; ana/analyze rebinds ana/*cljs-warnings* which we already did
                 ;; it seems to do this to get rid of duplicated warnings?
                 ;; we just do a distinct later
                 (ana/analyze* form nil opts)
                 (post-analyze state)))]

       (let [injected-forms @injected-forms-ref]
         (if-not (seq injected-forms)
           result
           ;; fake rewrite into a (do ...)
           {:op :do
            :env base-env
            :form form
            :children [:statements :ret]
            :statements injected-forms
            :ret result}))))))

(defn do-analyze-cljs-string
  [{:keys [resource-name cljc reader-features] :as init} reduce-fn cljs-source]
  (let [eof-sentinel (Object.)
        opts (merge
               {:eof eof-sentinel}
               (when cljc
                 {:read-cond :allow :features reader-features}))
        in (readers/indexing-push-back-reader (PushbackReader. (StringReader. cljs-source)) 1 resource-name)]

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
                        (:require-macros ns-info))

                      reader/resolve-symbol
                      ana/resolve-symbol]
              (reader/read opts in))]

        (if (identical? form eof-sentinel)
          ;; eof
          compile-state
          (recur (reduce-fn compile-state form)))))

    ))

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

  (when-not (get-in ast [:meta :skip-goog-provide])
    (comp/emitln "goog.provide('" (comp/munge name) "');"))

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
        #_(when (or (= type :goog)
                    (= type :cljs))
            (comp/emitln "goog.require('" (comp/munge dep) "');"))

        ;; in dev mode each CLJS files shadow.js.require their own js dependencies
        ;; since they might be loaded by the REPL or code-reloading.
        ;; in release mode that will only be done once since the GCC will complain otherwise
        ;; and we don't need to deal with code-reloading or the REPL anyways
        (when (= :shadow-js type)
          (comp/emitln "var " ns "=" (npm/shadow-js-require rc))
          )))))

(defn default-analyze-cljs
  [{:keys [last-progress-ref] :as state} {:keys [ns macros-ns] :as compile-state} form]
  ;; ignore (defmacro ...) in normal cljs compilation since they otherwise end
  ;; up as normal vars and can be called as fns. compile as usual when compiling as macro ns
  ;; FIXME: could use a better warning than "Use of undeclared Var demo.browser/dummy-macro"
  (if (and (not macros-ns)
           (list? form)
           (= 'defmacro (first form)))
    compile-state

    (let [{:keys [op] :as ast}
          (analyze state compile-state form)]

      ;; bump for every compiled form might be overkill
      (vreset! last-progress-ref (System/currentTimeMillis))

      (-> compile-state
          (update :ast conj ast)
          (cond->
            (= op :ns)
            (assoc
              :ns (:name ast)
              :ns-info (dissoc ast :env)))))))

(defn ns-wildcard-match? [pattern ns]
  (let [s (cond
            (symbol? pattern)
            (str pattern)
            (string? pattern)
            pattern
            :else
            (throw (ex-info "invalid :ignore entry" {:pattern pattern})))]
    (if (str/ends-with? s "*")
      (str/starts-with? (str ns) (subs s 0 (-> s (count) (dec))))
      (= s (str ns)))))

(comment
  (ns-wildcard-match? 'foo.* 'foo.bar))

(defn should-warning-throw?
  [state {:keys [ns] :as rc} {:keys [warning] :as warning-info}]
  (let [wae (get-in state [:compiler-options :warnings-as-errors])]
    (or (true? wae)
        (and (set? wae) (contains? wae warning))
        (and (map? wae)
             (let [{:keys [ignore warning-types]} wae]
               (if (and ns (seq ignore) (some #(ns-wildcard-match? % ns) ignore))
                 false
                 (or (not (seq warning-types))
                     (contains? warning-types warning)
                     )))))))

(comment
  (should-warning-throw?
    {:compiler-options
     {:warnings-as-errors
      {:ignore #{'foo.*}
       ;; :warning-types #{:undeclared-var}
       }}}
    {:ns 'foo.bar}
    {:warning :undeclared-var}))


(defn warning-collector [state warnings warning-type env extra]
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
          (ana/error-message warning-type extra)

          warning-info
          {:warning warning-type
           :line line
           :column column
           :msg msg}]

      (when (should-warning-throw? state *current-resource* warning-info)
        (throw (ex-info msg warning-info)))

      (swap! warnings conj warning-info))))

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

(defn analyze-cljs-string
  [state compile-state cljs-source]
  (with-warnings state
    (do-analyze-cljs-string
      compile-state
      (partial default-analyze-cljs state)
      cljs-source)))

(defn analyze-cljs-seq
  [state compile-state cljs-forms]
  (with-warnings state
    (reduce
      (partial default-analyze-cljs state)
      compile-state
      cljs-forms)))

(defn make-runtime-setup
  [state]
  (case (get-in state [:build-options :print-fn])
    :none ""
    ;; default to console
    "cljs.core.enable_console_print_BANG_();\n"))


(defn trigger-ready! [state rc]
  (when-let [ready-signal-fn (:ready-signal-fn state)]
    (ready-signal-fn rc)))

(defn compact-source-map [source-map]
  ;; the raw format is very verbose and takes a very long time to encode via transit
  ;; we don't actually need the verbose format after compilation and just preserve
  ;; the parts we really want for later when sources, sourcesContent get filled in
  ;; and maybe a few prepend lines get added
  (-> (sm/encode* {"unused" source-map} {})
      (select-keys ["mappings" "names"])))

(defn walk-ast [{:keys [children] :as ast} init visit-fn]
  (reduce
    (fn [result child-key]
      (let [child (get ast child-key)]
        (cond
          (map? child)
          (walk-ast child result visit-fn)

          (sequential? child)
          (reduce
            (fn [result child]
              (walk-ast child result visit-fn))
            result
            child)

          :else
          (throw (ex-info "unexpected :children entry, should be map or sequential?" {:ast ast}))
          )))
    (visit-fn init ast)
    children))

(defn do-compile-cljs-resource
  [{:keys [compiler-options] :as state}
   {:keys [resource-id resource-name from-jar] :as rc}
   source]
  (let [{:keys [warnings static-fns elide-asserts load-tests fn-invoke-direct infer-externs form-size-threshold]}
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
                  (merge warnings)
                  (cond->
                    (or (and (= :auto infer-externs) (not from-jar))
                        (= :all infer-externs))
                    (assoc :infer-warning true)))

              ana/*unchecked-arrays*
              ana/*unchecked-arrays*

              *assert*
              (not (true? elide-asserts))

              ana/*load-tests*
              (not (false? load-tests))

              *current-resource*
              rc]

      (util/with-logged-time
        [state {:type :compile-cljs :resource-id resource-id :resource-name resource-name}]

        (let [compile-init
              (-> {:resource-id resource-id
                   :resource-name resource-name
                   :source (str source)
                   :ns 'cljs.user
                   :ast []
                   :cljc (util/is-cljc? resource-name)
                   :reader-features (data/get-reader-features state)}
                  (cond->
                    (:macros-ns rc)
                    (assoc :macros-ns true)))

              {:keys [ns ast] :as output}
              (cond
                (string? source)
                (analyze-cljs-string state compile-init source)

                (vector? source)
                (analyze-cljs-seq state compile-init source)

                :else
                (throw (ex-info "invalid cljs source type" {:resource-id resource-id :resource-name resource-name :source source})))]

          ;; others can proceed compiling while we write output
          (trigger-ready! state rc)

          ;; 64kb initial capacity could probably be tuned
          ;; default is 16 bytes and we are definitely going to need more than that
          ;; safes a few array copies
          (let [sw (StringWriter. 65536)
                sm-ref (atom {:source-map (sorted-map)
                              :gen-col 0
                              :gen-line 0})

                size-warnings-ref
                (atom [])

                ;; track which vars a namespace references for later
                ;; plan to use it for some UI stuff.
                ;; could maybe implement some naive CLJS native DCE
                ;; by delaying emit until everything is analyzed and then
                ;; only emitting vars that were actually used
                used-vars
                (reduce
                  (fn [result ast-entry]
                    (walk-ast ast-entry result
                      (fn [result {:keys [op] :as ast}]
                        (if-not (contains? #{:var :js-var} op)
                          result
                          (conj result (:name ast))))))
                  #{}
                  ast)]

            (binding [comp/*source-map-data* sm-ref
                      comp/*source-map-data-gen-col* (AtomicLong.)
                      *out* sw]
              (doseq [ast-entry ast]
                (let [size-before (-> sw (.getBuffer) (.length))]
                  (shadow-emit state ast-entry)
                  (.flush sw)
                  (let [size-after (-> sw (.getBuffer) (.length))
                        diff (- size-after size-before)]
                    (when (and form-size-threshold (> diff form-size-threshold))
                      (let [warning {:warning :form-size-threshold
                                     :msg (str "Form produced " diff " bytes of JavaScript (exceeding your configured threshold)")
                                     :extra {:ns ns
                                             :resource-name resource-name
                                             :size diff}
                                     :line (get-in ast-entry [:env :line])
                                     :column (get-in ast-entry [:env :column])}]
                        (swap! size-warnings-ref conj warning)))))))

            (-> output
                (assoc :js (.toString sw)
                       :source-map-compact (compact-source-map (:source-map @sm-ref))
                       :compiled-at (System/currentTimeMillis)
                       :used-vars used-vars)
                (dissoc :ast)
                (update :warnings into @size-warnings-ref)
                (cond->
                  (= ns 'cljs.core)
                  (update :js str "\n" (make-runtime-setup state))
                  ))))))))

(defn get-cache-file-for-rc
  ^File [state {:keys [resource-name] :as rc}]
  (data/cache-file state "ana" (str resource-name ".cache.transit.json")))

(defn make-cache-key-map
  "produces a map of {resource-id cache-key} for caching to identify
   whether a cache is safe to use (if any cache-keys do not match if is safer to recompile)"
  [state rc]
  ;; FIXME: would it be enough to just use the immediate deps?
  ;; all seems like overkill but way safer
  (let [deps (data/get-deps-for-id state #{} (:resource-id rc))]

    ;; must always invalidate cache on version change
    ;; which will always have a new timestamp
    (-> {:SHADOW-TIMESTAMP SHADOW-TIMESTAMP}
        (util/reduce->
          (fn [cache-map id]
            (assoc cache-map id (get-in state [:sources id :cache-key])))
          deps))))

(def cache-affecting-options
  ;; paths into the build state
  ;; options which may effect the output of the CLJS compiler
  [[:mode]
   [:js-options :js-provider]
   [:compiler-options :form-size-threshold] ;; for tracking big suspicious code chunks
   [:compiler-options :source-map]
   [:compiler-options :source-map-inline]
   [:compiler-options :fn-invoke-direct]
   [:compiler-options :elide-asserts]
   [:compiler-options :reader-features]
   [:compiler-options :load-tests]
   [:compiler-options :data-readers]
   [:compiler-options :shadow-tweaks]
   [:compiler-options :warnings]
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
  [{:keys [compiler-env] :as state}
   {:keys [ns resource-id resource-name] :as rc}]
  (let [cache-file (get-cache-file-for-rc state rc)]

    (try
      (when (.exists cache-file)

        (util/with-logged-time
          [state {:type :cache-read
                  :resource-id resource-id
                  :resource-name resource-name}]

          (let [cache-data
                (cache/read-cache cache-file)

                cache-key-map
                (make-cache-key-map state rc)

                ana-data
                (:analyzer cache-data)]

            ;; just checking the "maximum" last-modified of all dependencies is not enough
            ;; must check times of all deps, mostly to guard against jar changes
            ;; lib-A v1 was released 3 days ago
            ;; lib-A v2 was released 1 day ago
            ;; we depend on lib-A and compile against v1 today
            ;; realize that a new version exists and update deps
            ;; compile again .. since we were compiled today the min-age is today
            ;; which is larger than v2 release date thereby using cache if only checking one timestamp

            (when (and (= cache-key-map (:cache-keys cache-data))
                       (macros/check-clj-info (:clj-info cache-data))
                       (every?
                         #(= (get-in state %)
                             (get-in cache-data [:compiler-options %]))
                         cache-affecting-options)

                       ;; check if lazy loaded namespaces that a given ns uses were moved to different modules
                       (let [lazy-refs (::lazy/ns-refs ana-data)]
                         (reduce-kv
                           (fn [_ ns assigned-module]
                             (or (= assigned-module (lazy/module-for-ns compiler-env ns))
                                 (reduced false)))
                           true
                           lazy-refs))

                       ;; check if any of the referenced resources were updated
                       (let [resource-refs (:shadow.resource/resource-refs ana-data)]
                         (reduce-kv
                           (fn [_ path prev-mod]
                             (let [rc (io/resource path)]
                               ;; check if the timestamps still match
                               ;; if the rc is gone or the timestamp changed invalidate the cache
                               (if (and rc (= prev-mod (util/url-last-modified rc)))
                                 true
                                 (reduced false))))
                           true
                           resource-refs)))

              ;; restore analysis data
              (swap! env/*compiler* update-in [::ana/namespaces (:ns cache-data)] merge ana-data)
              (swap! env/*compiler* update :shadow/protocol-prefixes set/union (:shadow/protocol-prefixes ana-data))
              (macros/load-macros ana-data)

              ;; restore specs
              (let [{:keys [ns-specs ns-spec-vars]} cache-data]
                (swap! cljs-spec/registry-ref merge ns-specs)

                ;; no idea why this is named so weirdly and private
                (let [priv-var (find-var 'cljs.spec.alpha/_speced_vars)]
                  (swap! @priv-var set/union ns-spec-vars)))

              (assoc (:output cache-data) :cached true)))))

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
    (try
      (util/with-logged-time
        [state {:type :cache-write
                :resource-id resource-id
                :resource-name resource-name
                :ns ns}]

        (let [cache-file
              (get-cache-file-for-rc state rc)

              cache-compiler-options
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
              ns-spec-vars
              (->> (cljs-spec/speced-vars)
                   (filter spec-filter-fn)
                   (into #{}))

              ana-data
              (get-in @env/*compiler* [::ana/namespaces ns])

              cache-data
              {:output output
               :cache-keys (make-cache-key-map state rc)
               :clj-info (macros/gather-clj-info state rc)
               :analyzer ana-data
               :ns ns
               :ns-specs ns-specs
               :ns-spec-vars ns-spec-vars
               :compiler-options cache-compiler-options}]

          ;; FIXME: the write can still happen before flush
          ;; should maybe safe this data somewhere instead and actually flush in flush
          ;; FIXME: is this thread-safe?
          (io/make-parents cache-file)
          (cache/write-file cache-file cache-data)))

      (catch Exception e
        (util/warn state {:type :cache-error
                          :action :write
                          :ns ns
                          :id resource-id
                          :error e})
        nil))))

(defn maybe-compile-cljs
  "take current state and cljs resource to compile
   make sure you are in with-compiler-env"
  [{:keys [build-options previously-compiled] :as state} {:keys [resource-id resource-name from-jar file url] :as rc}]
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

    (or (when (and cache? (not (contains? previously-compiled resource-id)))
          (when-let [output (load-cached-cljs-resource state rc)]
            (trigger-ready! state rc)
            output))
        (let [source
              (data/get-source-code state rc)

              output
              (try
                (do-compile-cljs-resource state rc source)
                (catch Exception e
                  (let [{:keys [type ex-kind] :as data}
                        (ex-data e)

                        line
                        (or (:clojure.error/line data)
                            (:line data))

                        column
                        (or (:clojure.error/column data) ;; 1.10.516+
                            (:column data) ;; cljs.analyzer
                            (:col data)) ;; tools.reader

                        err-data
                        (-> {:tag ::compile-cljs
                             :resource-id resource-id
                             :source-id resource-id
                             :url url
                             :file file
                             :ex-data data}

                            (cond->
                              ex-kind
                              (assoc :ex-kind ex-kind)

                              type
                              (assoc :ex-type type)

                              line
                              (assoc :line line)

                              column
                              (assoc :column column)

                              (and data line column)
                              (assoc :source-excerpt
                                     (let [[source-excerpt]
                                           (warnings/get-source-excerpts source [{:line line :column column}])]
                                       source-excerpt
                                       ))))]

                    (throw (ex-info (format "failed to compile resource: %s" resource-id) err-data e)))))]

          (when cache?
            (write-cached-cljs-resource state rc output))

          ;; fresh compiled, not from cache
          (assoc output :cached false)))))

(defn generate-output-for-source
  [state {:keys [resource-id] :as src}]
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
   {:keys [resource-id type requires extra-requires provides] :as src}]
  (assert (= :cljs type))
  (assert (set? requires))
  (assert (set? provides))

  ;; test targets dynamically add namespaces that we need to wait for
  ;; tracking this separately since messing with the ns dynamically is harder
  (let [requires (set/union requires extra-requires)]

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
            (maybe-compile-cljs state src)

            (catch Throwable e ;; asserts not covered by Exception
              (log/debug-ex e ::par-compile-ex {:resource-id resource-id})
              (swap! errors-ref assoc resource-id e)
              src
              )))))))

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

          ready-signal-fn
          (fn [{:keys [provides] :as src}]
            ;; FIXME: this does not seem ideal
            ;; maybe generate-output-for-source should expose the actual provides it generated

            ;; we need to mark aliases as ready as soon as a resource is ready
            ;; cljs.spec.alpha also provides clojure.spec.alpha only
            ;; if someone used the alias since that happened at resolve time
            ;; the resource itself does not provide the alias
            (let [provides
                  (reduce
                    (fn [provides provide]
                      (if-let [alias (get-in state [:ns-aliases-reverse provide])]
                        (conj provides alias)
                        provides))
                    provides
                    provides)]

              (swap! ready set/union provides)))

          state
          (assoc state :ready-signal-fn ready-signal-fn)

          already-compiled
          (->> sources
               (filter (fn [{:keys [resource-id] :as src}]
                         (map? (get-in state [:output resource-id])))))

          _ (doseq [src already-compiled]
              (ready-signal-fn src))

          already-compiled-set
          (->> already-compiled
               (map :resource-id)
               (into #{}))

          requires-compile
          (->> sources
               (remove #(contains? already-compiled-set (:resource-id %))))

          task-results
          (->> (for [src requires-compile]
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
          (throw (ex-info "compilation failed" {:tag ::fail-many :resource-ids (keys errs) :errors errs}))))

      (reduce
        (fn [state {:keys [resource-id] :as output}]
          (when (nil? output)
            (throw (ex-info "a compile task returned nil?" {})))
          (assert resource-id)
          (update state :output assoc resource-id output))
        (dissoc state :ready-signal-fn)
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

(defn compile-cljs-sources [{:keys [executor last-progress-ref] :as state} sources non-cljs-provides]
  ;; bump when starting a compile so watch doesn't cause timeouts
  (vreset! last-progress-ref (System/currentTimeMillis))

  (let [parallel-build (get-in state [:compiler-options :parallel-build])]
    (-> state
        (closure/load-extern-properties)
        (use-extern-properties)
        (cond->
          (and executor (not (false? parallel-build)))
          (par-compile-cljs-sources sources non-cljs-provides)

          ;; seq compile doesn't really need the provides since it doesn't need to coordinate threads
          (or (false? parallel-build) (not executor))
          (seq-compile-cljs-sources sources)))))

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

(defn remove-dead-js-deps [{:keys [dead-js-deps] :as state}]
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
  (let [{:keys [require-id] :as rc} (data/get-source-by-id state resource-id)
        deps-syms (data/deps->syms state rc)

        require-ids
        (->> deps-syms
             (map #(get-in state [:sym->require-id %]))
             (remove nil?)
             (into #{}))]

    (update-in state [:sources resource-id :cache-key]
      (fn [key]
        ;; FIXME: this still isn't clean but nothing should be allowed to modify
        ;; cache-key beyond this point
        ;; must make sure we don't keep appending the same data in watch
        (-> (take-while #(not= ::resolve %) key)
            (vec)
            (conj
              ::resolve
              {:require-id require-id
               :deps-ids require-ids
               :deps-syms deps-syms}))))))

(defn throw-js-errors-now! [state]
  (doseq [{:keys [file js-errors source] :as src} (data/get-build-sources state)
          :when (seq js-errors)]
    (let [excerpts (warnings/get-source-excerpts source js-errors)
          merged (vec (map (fn [loc ex] (assoc loc :source-excerpt ex)) js-errors excerpts))]

      (throw (ex-info "Invalid JS input" {:tag ::js-error
                                          :file file
                                          :js-errors merged})))))

(defn assign-require-ids [state source-ids]
  (loop [state state
         [src-id & more] source-ids
         idx 0]
    (if-not src-id
      state
      (let [{:keys [ns type] :as src} (get-in state [:sources src-id])]
        (if (not= :shadow-js type)
          (recur state more idx)

          ;; assign an alias to be used when setting up the provide and on each require
          ;; shadow$provide[some-alias] = function(...) { require(some-alias); }
          ;; typically defaults to the full namespace which has a pretty big impact on overall
          ;; size on libraries that contains a lot of small files that all require each other in some way
          ;;
          ;; a simple numeric id is the best for overall size (after gzip) but doesn't cache well
          ;; since every added JS dep may shift the ids and thus require recompiles
          ;;
          ;; using the file ns in hash form is overall shorter than most paths but downright hostile
          ;; when it comes to gzip compression and thus not viable
          ;;
          ;; even a shortened hashes still basically nullify gains in gzip
          ;;
          ;; full hash    [JS: 825.62 KB] [GZIP: 205.61 KB]
          ;; full path    [JS: 891.56 KB] [GZIP: 186.04 KB]
          ;; short hash 8 [JS: 745.50 KB] [GZIP: 184.36 KB] (first 8 chars)
          ;; short hash 6 [JS: 738.83 KB] [GZIP: 182.84 KB] (first 6 chars)
          ;; numeric id   [JS: 719.89 KB] [GZIP: 177.36 KB]
          ;;
          ;; don't know how short the hash could be to reduce conflicts since each conflict
          ;; would mean we need to recompile which then makes it worse than numeric ids
          ;;
          ;; wonder if there is something that is gzip-friendly but also cache-friendly

          (let [alias idx #_(subs (util/md5hex (str ns)) 0 6)]
            (-> state
                (update-in [:sources src-id] assoc :require-id alias)
                (assoc-in [:require-id->sym alias] ns)
                (assoc-in [:sym->require-id ns] alias)
                (recur more (inc idx))
                )))))))

(defn compile-all
  "compile a list of sources by id,
   requires that the ids are in dependency order
   requires that ALL of the dependencies NOT listed are already compiled
   eg. you cannot just compile clojure.string as it requires other files to be compiled first "
  ([{:keys [build-sources] :as state}]
   (compile-all state build-sources))
  ([{:keys [mode build-sources] :as state} source-ids]
   ;; throwing js parser errors here so they match other error sources
   ;; as other errors will be thrown later on in this method as well
   (throw-js-errors-now! state)

   (cljs-hacks/install-hacks!)

   (let [state
         (if-not (get-in state [:js-options :minimize-require])
           state
           (assign-require-ids state source-ids))

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

         ;; is a non-cljs file is used as an alias we need to remember that for
         ;; parallel compile since it will otherwise idle until failure
         non-cljs-provides
         (->> non-cljs-provides
              (map #(get-in state [:ns-aliases-reverse %]))
              (remove nil?)
              (into non-cljs-provides))

         optimizing?
         (let [x (get-in state [:compiler-options :optimizations])]
           (or (nil? x)
               (not= x :none)))

         ;; used by shadow-resolve-var to determine if rogue dotted symbols could be actual CLJS symbols
         ;; sorted by descending length to avoid bailing too early
         ;; some.foo.bar.thing should always hit some.foo.bar and never some.foo if both exist
         cljs-provides
         (into #{} (map :ns) cljs)

         ;; work around problems where rrb-vector uses direct reference to
         ;; clojure.core.rrb_vector.rrbt.Vector
         ;; which will never be found in analyzer data since it should be
         ;; clojure.core.rrb-vector.rrbt/Vector
         ;; https://github.com/clojure/core.rrb-vector/blob/88c605a72f1176813ca71d664275d480285f634e/src/main/cljs/clojure/core/rrb_vector/macros.clj#L23-L24
         ;; only doing this to relax warnings given that this is a probably-wont-fix in CLJS
         cljs-provides
         (->> cljs-provides
              (map comp/munge)
              (into cljs-provides)
              (sort-by #(-> % str count))
              (reverse)
              (into []))

         goog-provides
         (->> non-cljs-provides
              (sort-by #(-> % str count))
              (reverse)
              (into []))

         ns-roots
         (->> (concat cljs goog js)
              (mapcat :provides)
              (into #{})
              (map str)
              (map (fn [ns]
                     (if-let [idx (str/index-of ns ".")]
                       (subs ns 0 idx)
                       ns)))
              (into #{}))]

     (-> state
         (assoc :compile-start (System/currentTimeMillis))

         ;; cljs.compiler/munge needs to know which variables become namespace roots
         ;; so that (let [thing ...] ...)
         ;; doesn't clash with (ns thing.foo.bar)
         ;; but due to parallel compilation the ns may not exist when munge decides to munge
         ;; so we ensure the roots always exist even before compilation starts
         (update :compiler-env merge {:shadow/ns-roots ns-roots
                                      :shadow/cljs-provides cljs-provides
                                      :shadow/goog-provides goog-provides})

         (assoc-in [:compiler-env ::lazy/ns->mod]
           (->> (for [{:keys [module-id sources]} (:build-modules state)
                      src-id sources
                      :let [module-s (name module-id)
                            {:keys [provides] :as rc} (get-in state [:sources src-id])]
                      provide provides]
                  [provide module-s])
                (into {})))

         ;; order of this is important
         ;; CLJS first since all it needs are the provided names
         (cond->
           ;; goog
           ;; release builds go through the closure compiler and we want to avoid processing goog sources twice
           (and (= :release mode) (seq goog))
           (copy-source-to-output goog)

           (and (= :dev mode) (seq goog))
           (maybe-closure-convert goog closure/convert-goog)

           ;; classpath-js, meaning ESM code on the classpath
           ;; in dev process classpath-js now, including polyfills
           (and (= :dev mode) (seq js))
           (maybe-closure-convert js closure/convert-sources)

           ;; in release just copy classpath-js and run through regular optimizations
           (and (= :release mode) (seq js))
           (closure/classpath-js-copy js)

           ;; shadow-js, node_modules or commonjs on the classpath
           ;; shadow-js is always separate, optimized separately
           (seq shadow-js)
           (maybe-closure-convert shadow-js closure/convert-sources-simple)

           ;; cljs
           ;; do this last as it uses data from above
           (seq cljs)
           (compile-cljs-sources cljs non-cljs-provides))

         ;; remember which sources were compiled for watch mode
         ;; otherwise it will attempt to load cache from disk although
         ;; that just got invalidated elsewhere
         (update :previously-compiled into build-sources)
         (remove-dead-js-deps)

         (closure/make-polyfill-js)
         (assoc :compile-finish (System/currentTimeMillis))

         ;; (?-> ::compile-finish)
         ))))
