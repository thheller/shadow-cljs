(ns shadow.build.cljs-hacks
  (:require
    [clojure.string :as str]
    [cljs.analyzer :as ana]
    [cljs.compiler :as comp]
    [cljs.env :as env]
    [cljs.core :as core]
    [cljs.test :as test]
    [shadow.debug :as dbg :refer (?> ?-> ?->>)]
    [shadow.jvm-log :as log]))

;; replacing some internal cljs.analyzer/compiler fns
;; since they aren't configurable/extendable enough

(def conj-to-set (fnil conj #{}))

(defn shadow-js-access-global [current-ns global]
  {:pre [(symbol? current-ns)
         (string? global)]}
  (swap! env/*compiler* update-in
    [::ana/namespaces current-ns :shadow/js-access-global] conj-to-set global))

(defn shadow-js-access-property [current-ns prop]
  {:pre [(symbol? current-ns)
         (string? prop)]}
  (when-not (or (= prop "prototype")
                (str/starts-with? prop "cljs$")
                ;; should never collect any protocol properties
                ;; extending protocols on JS objects
                (some #(str/starts-with? prop %) (:shadow/protocol-prefixes @env/*compiler*)))

    (swap! env/*compiler* update-in
      [::ana/namespaces current-ns :shadow/js-access-properties] conj-to-set prop)))

(defn is-shadow-shim? [ns]
  (str/starts-with? (str ns) "shadow.js.shim"))

(defn resolve-js-var [ns sym current-ns]
  ;; quick hack to record all accesses to any JS mod
  ;; (:require ["react" :as r :refer (foo]) (r/bar ...)
  ;; will record foo+bar

  (let [prop (name sym)

        {:keys [js-esm type]}
        (get-in @env/*compiler* [:shadow/js-namespaces ns])

        qname
        (symbol "js" (str ns "." prop))]

    ;; only generate externs for js access to shadow-js compiled files
    ;; :goog and closure-transformed :js should not generate externs
    ;; :goog shim files for :require should always generate though
    (when (or (= :shadow-js type)
              (is-shadow-shim? ns))
      (shadow-js-access-property current-ns prop))

    {:op :js-var
     :name qname
     :tag 'js
     :ret-tag 'js
     :ns 'js}))

;; there is one bad call in cljs.analyzer/resolve-var
;; which calls this with a string checking the UNRESOLVED alias
;; (:require [something :as s])
;; (s/foo)
;; calls (js-module-exists? "s") which seems like a bug
;; FIXME: report JIRA issue
(defn js-module-exists? [module]
  {:pre [(symbol? module)]}
  (some? (get-in @env/*compiler* [:shadow/js-namespaces module])))

(defn resolve-cljs-var [ns sym]
  (merge (ana/gets @env/*compiler* ::ana/namespaces ns :defs sym)
    {:op :var
     :name (symbol (str ns) (str sym))
     :ns ns}))

(defn resolve-ns-var [ns sym current-ns]
  (cond
    ;; must ensure that CLJS vars resolve first
    (contains? (:cljs.analyzer/namespaces @env/*compiler*) ns)
    (resolve-cljs-var ns sym)

    (or (js-module-exists? ns)
        (is-shadow-shim? ns))
    (resolve-js-var ns sym current-ns)

    (contains? (:goog-names @env/*compiler*) ns)
    {:op :js-var
     :name (symbol (str ns) (str sym))
     :ns ns}

    :else
    (resolve-cljs-var ns sym)
    ))

;; ana/resolve-ns-alias converts to symbols too much, we already only have symbols
(defn resolve-ns-alias [env alias]
  (ana/gets env :ns :requires alias))

(defn invokeable-ns?
  "Returns true if ns is a required namespace and a JavaScript module that
   might be invokeable as a function."
  [alias env]
  (when-let [ns (resolve-ns-alias env alias)]
    (or (js-module-exists? ns)
        (is-shadow-shim? ns)
        )))

(defn resolve-invokeable-ns [alias current-ns env]
  (let [ns (resolve-ns-alias env alias)]
    {:op :js-var
     :name (symbol "js" (str ns))
     :tag 'js
     :ret-tag 'js
     :ns 'js}))

;; core.async calls `macroexpand-1` manually with an ill-formed
;; :locals map. Normally :locals maps symbols maps, but
;; core.async adds entries mapping symbols to symbols. We work
;; around that specific case here. This is called defensively
;; every time we lookup the :locals map.
(defn handle-symbol-local [sym lb]
  (if (symbol? lb)
    {:name sym}
    lb))

(def known-safe-js-globals
  "symbols known to be closureJS compliant namespaces"
  #{"cljs"
    "goog"
    "console"})

(defn potential-ns-match? [cljs-namespaces s]
  (reduce
    (fn [_ sym]
      (when (str/starts-with? s (name sym))
        (reduced sym)))
    nil
    cljs-namespaces))

(comment
  (potential-ns-match? '[a.b.c a.b] "a.b.c.d"))

(defn shadow-resolve-var
  "Resolve a var. Accepts a side-effecting confirm fn for producing
   warnings about unresolved vars."
  ([env sym]
   (shadow-resolve-var env sym nil))
  ([env sym confirm]
   (let [locals (:locals env)
         current-ns (-> env :ns :name)
         sym-ns-str (namespace sym)]

     (if (= "js" sym-ns-str)
       ;; js/... vars
       (let [symn (-> sym name symbol)
             shadowed-by-local (handle-symbol-local symn (get locals symn))]
         (cond
           (some? shadowed-by-local)
           (do (ana/warning :js-shadowed-by-local env {:name sym})
               (assoc shadowed-by-local :op :local))

           :else
           ;; always record all fully qualified js/foo.bar calls
           ;; except for the code emitted by cljs.core/exists?
           ;; (exists? some.foo/bar)
           ;; checks js/some, js/some.foo, js/some.foo.bar
           (do (when (and (not (-> sym meta ::ana/no-resolve))
                          ;; when resolve is called from tools.reader to resolve syntax quotes
                          ;; the reading env :ns is not set
                          ;; as env won't be the analyzer env but the full compiler env
                          ;; and we don't need to resolve externs just yet
                          current-ns)

                 (let [[global & props]
                       (str/split (name sym) #"\.")]

                   ;; do not record access to known namespace roots
                   ;; js/goog.string.format
                   ;; js/cljs.core.assoc
                   ;; just in case someone does that, we won't need externs for those
                   (let [ns-roots (:shadow/ns-roots @env/*compiler*)]
                     (when-not (or (contains? ns-roots global)
                                   ;; just in case ns-roots wasn't set properly
                                   (contains? known-safe-js-globals global))

                       (shadow-js-access-global current-ns global)
                       (when (seq props)
                         (doseq [prop props]
                           (shadow-js-access-property current-ns prop)))))))

               ;; FIXME: exists? ends up here since it will check all segments
               ;; (defonce PROTOCOL-SENTINEL ...)
               ;; js/cljs
               ;; js/cljs.core
               ;; js/cljs.core.PROTOCOL-SENTINEL
               ;; they don't leak into the code but should technically not return :js-var for those?
               {:op :js-var
                :name sym
                :ns 'js
                :tag 'js
                :ret-tag 'js})))

       ;; none js/... vars
       (let [s (str sym)
             lb (handle-symbol-local sym (get locals sym))
             current-ns-info (ana/gets @env/*compiler* ::ana/namespaces current-ns)]

         (cond
           (some? lb) (assoc lb :op :local)

           (some? sym-ns-str)
           (let [ns sym-ns-str
                 ns (symbol (if (= "clojure.core" ns) "cljs.core" ns))
                 full-ns (or (resolve-ns-alias env ns) ns) ;; [some.thing :as x] x->some.thing OR some.thing
                 ;; strip ns
                 sym (symbol (name sym))]
             (when (some? confirm)
               (when (not= current-ns full-ns)
                 (ana/confirm-ns env full-ns))
               (confirm env full-ns sym))
             (resolve-ns-var full-ns sym current-ns))

           (some? (ana/gets current-ns-info :uses sym))
           (let [full-ns (ana/gets current-ns-info :uses sym)]
             (resolve-ns-var full-ns sym current-ns))

           (some? (ana/gets current-ns-info :renames sym))
           (let [qualified-symbol (ana/gets current-ns-info :renames sym)
                 full-ns (symbol (namespace qualified-symbol))
                 sym (symbol (name qualified-symbol))]
             (resolve-ns-var full-ns sym current-ns))

           (some? (ana/gets current-ns-info :imports sym))
           (recur env (ana/gets current-ns-info :imports sym) confirm)

           (some? (ana/gets current-ns-info :defs sym))
           (do (when (some? confirm)
                 (confirm env current-ns sym))
               (resolve-cljs-var current-ns sym))

           ;; https://dev.clojure.org/jira/browse/CLJS-2380
           ;; not sure if correct fix
           ;; cljs.core/Object is used by parse-type so using that here
           (= 'Object sym)
           '{:op :var
             :name cljs.core/Object
             :ns cljs.core}

           (ana/core-name? env sym)
           (do (when (some? confirm)
                 (confirm env 'cljs.core sym))
               (resolve-cljs-var 'cljs.core sym))

           (invokeable-ns? sym env)
           (resolve-invokeable-ns sym current-ns env)

           ;; any symbol with a dot but no namespace, already checked all renames/imports/requires
           ;; so that (:require ["some-js" :as foo.bar]) (foo.bar)
           ;; works properly and isn't desugared into anything else
           (ana/dotted-symbol? sym)
           (let [idx (.indexOf s ".")
                 prefix (subs s 0 idx)
                 prefix-sym (symbol prefix)
                 suffix (subs s (inc idx))]
             ;; short circuit if a direct reference to a fully qualify closure name is used
             ;; goog.math.Integer
             ;; since those are always JS and should be invoked as such
             (if (contains? (:shadow/goog-provides @env/*compiler*) sym)
               {:op :js-var
                :name (symbol "js" s)
                :ns 'js}
               ;; "some.thing" checks if there is a local "some"
               (if-some [lb (handle-symbol-local prefix-sym (get locals prefix-sym))]
                 {:op :local
                  :name (symbol (str (:name lb) "." suffix))}
                 ;; (:import [goog.thing EventType]) EventType.NAVIGATE
                 (if-some [full-ns (ana/gets current-ns-info :imports prefix-sym)]
                   {:op :local
                    :name (symbol (str full-ns) suffix)}
                   ;; "some.thing" checks if there is a (def some #js {...}) in current ns
                   (if-some [info (ana/gets current-ns-info :defs prefix-sym)]
                     (merge info
                       {:op :local
                        :name (symbol (str current-ns) (str sym))
                        :ns current-ns})

                     ;; attempt to fix
                     ;; https://dev.clojure.org/jira/browse/CLJS-712
                     ;; https://dev.clojure.org/jira/browse/CLJS-2957
                     ;; FIXME: patch this properly in clojurescript so we don't need this hackery

                     ;; CLJS defaults to just resolving everything with a dot in it and is completely broken
                     ;; process.env.FOO ends up as process/env.FOO and never warns
                     ;; cljs.core.-invoke ends up as cljs/core.-invoke
                     ;; ANY symbol with a dot should not automatically resolve magically without any further checks

                     ;; I don't see how this could ever resolve something useful but this is what CLJS does
                     (if-let [last-hope (ana/gets @env/*compiler* ::ana/namespaces prefix-sym :defs (symbol suffix))]
                       (merge last-hope
                         {:op :local
                          :name (if (= "" prefix-sym) (symbol suffix) (symbol (str prefix-sym) suffix))
                          :ns prefix-sym})

                       (let [{:shadow/keys [goog-provides cljs-provides]} @env/*compiler*]
                         ;; matches goog.DEBUG since goog is provided but goog.DEBUG was no explicit provide
                         (if (potential-ns-match? goog-provides s)
                           {:op :js-var
                            :name (symbol "js" s)
                            :ns 'js}

                           ;; tailrecursion.priority-map.PersistentPriorityMap.EMPTY
                           ;; is technically an incorrect symbol but munges to the correct one
                           ;; references like this should not warn since there are far too many of them
                           ;; so if the symbol starts with a known CLJS ns prefix we'll accept it
                           ;; although it may not actually contain any analyzer data
                           ;;
                           ;; cannot blindly accept by ns-root since clojure.string creates clojure
                           ;; but clojure.lang does not exist so didn't warn about clojure.lang.MapEntry
                           (let [hit (potential-ns-match? cljs-provides s)]
                             (cond
                               ;; (exists? some.cljs.ns/foo) will emit a runtime lookup for some, some.cljs, some.cljs.ns
                               ;; but not actually use them in any other way so lets pretend this is a raw JS var
                               (= hit sym)
                               {:op :js-var
                                :name (symbol "js" s)
                                :ns 'js}

                               ;; partial match
                               hit
                               (let [guessed-ns hit
                                     guessed-sym (symbol (subs s (-> hit str count inc)))]

                                 ;; this path happens way too often and should be fixed properly
                                 #_(log/debug ::autofix-symbol
                                     {:sym sym
                                      :guessed-ns guessed-ns
                                      :guessed-sym guessed-sym})

                                 ;; although this split will sometimes produce valid matches it won't always work
                                 ;; cljs.core.-invoke works and ens up as cljs.core/-invoke
                                 ;; tailrecursion.priority-map.PersistentPriorityMap.EMPTY
                                 ;; ends as tailrecursion.priority-map/PersistentPriorityMap.EMPTY
                                 ;; since EMPTY is a property the analyzer doesn't not anything about

                                 (merge (ana/gets @env/*compiler* ::ana/namespaces guessed-ns :defs guessed-sym)
                                   {:op :var
                                    :name (symbol (str guessed-ns) (str guessed-sym))
                                    :ns guessed-ns}))

                               ;; not known namespace root, resolve as js/ as a last ditch effort
                               ;; this should probably hard fail instead but that would break too many builds
                               ;; resolving as js/* is closer to the default behavior
                               ;; and will most likely be for cases where we are actually accessing a global
                               ;; ala process.env.FOO which will then warn properly
                               :else
                               (do (when (some? confirm)
                                     (confirm env current-ns sym))
                                   {:op :js-var
                                    :name (symbol "js" s)
                                    :ns 'js}
                                   )))))))))))

           :else
           (do (when (some? confirm)
                 (confirm env current-ns sym))
               (resolve-cljs-var current-ns sym)
               )))))))

(defn shadow-resolve-var-checked
  ([env sym]
   (shadow-resolve-var-checked env sym nil))
  ([env sym confirm]
   (let [info (shadow-resolve-var env sym confirm)]
     (when-not (:op info)
       (throw (ex-info "missing op" {:sym sym})))
     info
     )))

(defn infer-externs-dot
  [{:keys [form form-meta method field target target-tag env prop tag] :as ast}
   {:keys [infer-externs] :as opts}]
  (when infer-externs
    (let [sprop
          (str (or method field))

          ;; added by cljs.core/add-obj-methods
          ;; used by deftype/defrecord when adding functions to Object
          ;; without tag we record the property for externs
          ;; if tagged with anything but ^js we skip the record
          shadow-object-fn
          (:shadow/object-fn form-meta)]

      ;; never need to record externs for any prop we already have externs for
      (when-not (contains? (:shadow/js-properties @env/*compiler*) sprop)

        ;; simplified *warn-on-infer* warnings since we do not care about them being typed
        ;; we just need ^js not ^js/Foo.Bar
        (when (and (:infer-warning ana/*cljs-warnings*) ;; skip all checks if the warning is ignored anyways
                   (not= "prototype" sprop)
                   (not= "constructor" sprop)
                   ;; defrecord
                   (not= "getBasis" sprop)
                   (not (str/starts-with? sprop "cljs$"))
                   (or (nil? tag) (= 'any tag))
                   (or (nil? target-tag) (= 'any target-tag))
                   ;; protocol fns, never need externs for those
                   (not (str/includes? sprop "$arity$"))
                   (not (contains? (:shadow/protocol-prefixes @env/*compiler*) sprop))
                   (not shadow-object-fn)
                   ;; no immediate ideas how to annotate these so it doesn't warn
                   (not= form '(. cljs.core/List -EMPTY))
                   (not= form '(. cljs.core/PersistentVector -EMPTY-NODE))

                   ;; don't need to warn about anything from goog.*
                   ;; {:op :js-var :name js/goog.net.ErrorCode}
                   (not (and (= :js-var (:op target))
                             (str/starts-with? (-> target :name name) "goog."))))

          (ana/warning :infer-warning env {:warn-type :target :form form}))

        (when (and shadow-object-fn
                   (let [tag (-> shadow-object-fn meta :tag)]
                     (or (nil? tag) (= 'js tag))))
          (shadow-js-access-property
            (-> env :ns :name)
            (str shadow-object-fn)))

        (when (ana/js-tag? tag)
          (shadow-js-access-property
            (-> env :ns :name)
            sprop
            ))))))

(defn shadow-analyze-dot [env target member member+ form opts]
  (when (nil? target)
    (throw (ex-info "Cannot use dot form on nil" {:form form})))

  (let [member-sym? (symbol? member)
        member-seq? (seq? member)
        prop-access? (and member-sym? (= \- (first (name member))))

        ;; common for all paths
        enve (assoc env :context :expr)
        targetexpr (ana/analyze enve target)
        form-meta (meta form)
        target-tag (:tag targetexpr)
        tag (or (:tag form-meta)
                (and (ana/js-tag? target-tag) 'js)
                nil)

        ast
        {:env env
         :form form
         :form-meta form-meta
         :target targetexpr
         :target-tag target-tag
         :children [:target]
         :tag tag}

        ast
        (cond
          ;; (. thing (foo) 1 2 3)
          (and member-seq? (seq member+))
          (throw (ex-info "dot with extra args" {:form form}))

          ;; (. thing (foo))
          ;; (. thing (foo 1 2 3))
          member-seq?
          (let [[method & args] member
                argexprs (mapv #(ana/analyze enve %) args)]
            (assoc ast :op :host-call
                       :prop method
                       :method method
                       :children [:target :args]
                       :args argexprs))

          ;; (. thing -foo 1 2 3)
          (and prop-access? (seq member+))
          (throw (ex-info "dot prop access with args" {:form form}))

          ;; (. thing -foo)
          prop-access?
          (assoc ast :op :host-field
                     :prop member
                     :field (-> member (name) (subs 1) (symbol)))

          ;; (. thing foo)
          ;; (. thing foo 1 2 3)
          member-sym?
          (let [argexprs (mapv #(ana/analyze enve %) member+)]
            (assoc ast :op :host-call
                       :prop member
                       :method member
                       :children [:target :args]
                       :args argexprs))

          :else
          (throw (ex-info "invalid dot form" {:form form})))]

    (infer-externs-dot ast opts)

    ast))

(defmethod ana/parse '.
  [_ env [_ target field & member+ :as form] _ opts]
  (ana/disallowing-recur (shadow-analyze-dot env target field member+ form opts)))

;; its private in cljs.compiler ...
(def es5>=
  (into #{}
    (comp
      (mapcat (fn [lang]
                [lang (keyword (str/replace (name lang) #"^ecmascript" "es"))])))
    [:ecmascript5 :ecmascript5-strict :ecmascript6 :ecmascript6-strict
     :ecmascript-2015 :ecmascript6-typed :ecmascript-2016 :ecmascript-2017
     :ecmascript-next]))

(defn shadow-emit-var
  [{:keys [info env form] :as ast}]
  (if-let [const-expr (:const-expr ast)]
    (comp/emit (assoc const-expr :env env))
    (let [var-name (:name info)]

      ;; We need a way to write bindings out to source maps and javascript
      ;; without getting wrapped in an emit-wrap calls, otherwise we get
      ;; e.g. (function greet(return x, return y) {}).
      (cond
        ;; Emit the arg map so shadowing is properly handled when munging
        ;; (prevents duplicate fn-param-names)
        (:binding-form? ast)
        (comp/emits (comp/munge ast))

        (= 'js/-Infinity form)
        (comp/emits "-Infinity")

        ;; insufficient fix still rewriting unless es5+
        ;; https://dev.clojure.org/jira/browse/CLJS-1620
        ;; instead of munging it should just emit ["default"] instead of .default
        ;; FIXME: this only covers .default when used with js/
        ;; which _should_ be ok since all generated code should be munged properly
        ;; just shadow-js and js compiled by goog is not munged but instead uses [] access
        (and var-name (= (namespace var-name) "js"))
        (comp/emit-wrap env
          (let [[head & tail] (str/split (name var-name) #"\.")]
            (comp/emits (comp/munge head))
            (doseq [part tail]
              (cond
                ;; FIXME: should properly check if output is ES3
                (= "default" part)
                (comp/emits ".default")

                (and (contains? ana/js-reserved part)
                     (not (-> var-name meta ::ana/no-resolve)))
                (comp/emits "[\"" part "\"]")

                :else
                (comp/emits "." (comp/munge part))))))

        :else
        (when-not (= :statement (:context env))
          (comp/emit-wrap env
            (comp/emits (comp/munge info))))))))

(defmethod comp/emit* :var [expr] (shadow-emit-var expr))
(defmethod comp/emit* :binding [expr] (shadow-emit-var expr))
(defmethod comp/emit* :js-var [expr] (shadow-emit-var expr))
(defmethod comp/emit* :local [expr] (shadow-emit-var expr))

;; no perf impact, just easier to read
(defn source-map-inc-col [{:keys [gen-col] :as m} n]
  (assoc m :gen-col (+ gen-col n)))

(defn source-map-inc-line [{:keys [gen-line] :as m}]
  (assoc m
    :gen-line (inc gen-line)
    :gen-col 0))

;; string? provides pretty decent boost
(defn emit1 [x]
  (cond
    (nil? x) nil
    (string? x)
    (do (when-not (nil? comp/*source-map-data*)
          (swap! comp/*source-map-data* source-map-inc-col (count x)))
        (print x))
    (map? x) (comp/emit x)
    (seq? x) (run! emit1 x)
    (fn? x) (x)
    :else (let [s (print-str x)]
            (when-not (nil? comp/*source-map-data*)
              (swap! comp/*source-map-data* source-map-inc-col (count s)))
            (print s))))

(defn shadow-emits [& xs]
  (run! emit1 xs))

(defn shadow-emitln [& xs]
  (run! emit1 xs)
  (newline)
  (when comp/*source-map-data*
    (swap! comp/*source-map-data* source-map-inc-line))
  nil)

;; noop
(defn shadow-load-core [])

;; cljs.test tweaks
(defmacro shadow-deftest
  "Defines a test function with no arguments.  Test functions may call
  other tests, so tests may be composed.  If you compose tests, you
  should also define a function named test-ns-hook; run-tests will
  call test-ns-hook instead of testing all vars.

  Note: Actually, the test body goes in the :test metadata on the var,
  and the real function (the value of the var) calls test-var on
  itself.

  When cljs.analyzer/*load-tests* is false, deftest is ignored."
  [name & body]
  (when ana/*load-tests*
    `(do
       (def ~(vary-meta name assoc :test `(fn [] ~@body))
         (fn [] (cljs.test/test-var (.-cljs$lang$var ~name))))

       (let [the-var# (var ~name)]
         (set! (.-cljs$lang$var ~name) the-var#)
         (shadow.test.env/register-test (quote ~(symbol (str *ns*))) (quote ~name) the-var#)
         ))))

(defmacro shadow-use-fixtures [type & fns]
  {:pre [(contains? #{:once :each} type)]}
  `(shadow.test.env/register-fixtures (quote ~(symbol (str *ns*))) ~type [~@fns]))

;; CLJS by default iterates through all namespaces and checks if the first segment
;; matches the var-name. since we know all namespace roots before compilation even
;; starts we can do this by checking the set of ns-roots (strings) created in
;; shadow.cljs.compiler/compile-all
(defn shadow-find-ns-starts-with [var-name]
  (let [ns-roots (:shadow/ns-roots @env/*compiler*)]
    (assert (set? ns-roots))
    (contains? ns-roots var-name)))

(defn replace-fn! [the-var the-fn]
  (when (not= @the-var the-fn)
    (log/debug ::replace-fn! {:the-var the-var})
    (alter-var-root the-var (fn [_] the-fn))))


;; overriding for the closure-compiler, closure-library
;; releases where goog.define must be assigned and will otherwise throw
;; which makes this actually much easier to use
(defn goog-define
  "Defines a var using `goog.define`. Passed default value must be
  string, number or boolean.

  Default value can be overridden at compile time using the
  compiler option `:closure-defines`.

  Example:
    (ns your-app.core)
    (goog-define DEBUG! false)
    ;; can be overridden with
    :closure-defines {\"your_app.core.DEBUG_BANG_\" true}
    or
    :closure-defines {'your-app.core/DEBUG! true}"
  [&form &env sym default]
  (let [defname (comp/munge (str *ns* "/" sym))
        type (cond
               (string? default) "string"
               (number? default) "number"
               (or (true? default) (false? default)) "boolean")]

    `(def ~(vary-meta sym
             (fn [m]
               (-> m
                   (update :jsdoc conj (str "@define {" type "}"))
                   (cond->
                     (not (contains? m :tag))
                     (assoc :tag (symbol type))))))
       (js/goog.define ~defname ~default))))

;; https://github.com/clojure/clojurescript/commit/1589e5848ebb56ab451cb73f955dbc0b01e7aba0
;; oops, seem to have missed keyword?
(defn shadow-all-values? [exprs]
  (every? #(or (nil? %) (symbol? %) (string? %) (number? %) (keyword? %) (true? %) (false? %)) exprs))

(comment
  (defn has-required-arity? [mps argc]
    (reduce
      (fn [_ params]
        (when (= (count params) argc)
          (reduced true)))
      false mps))

  ;; I do not like private ...
  (defn record-tag?
    [tag]
    (boolean (and (symbol? tag)
                  (some? (namespace tag))
                  (get-in @env/*compiler* [::ana/namespaces (symbol (namespace tag)) :defs (symbol (name tag)) :record]))))

  (defn record-basis
    [tag]
    (let [positional-factory (symbol (str "->" (name tag)))
          fields (first (get-in @env/*compiler* [::ana/namespaces (symbol (namespace tag)) :defs positional-factory :method-params]))]
      (into #{} fields)))

  (defn record-with-field?
    [tag field]
    (and (record-tag? tag)
         (contains? (record-basis tag) field)))

  (defn make-invoke [invoke-type env form fexpr args-exprs]
    {:op :invoke
     :env env
     :fn fexpr
     :args args-exprs
     :invoke-type invoke-type
     :form form
     :children [:fn :args]})

  (defn shadow-parse-invoke*
    [env [f & args :as form]]
    (let
      [enve (assoc env :context :expr)
       fexpr (ana/analyze enve f)

       argc (count args)
       ftag (ana/infer-tag env fexpr)

       args-exprs (mapv #(ana/analyze enve %) args)

       info (:info fexpr)
       ;; fully-qualified symbol name of protocol-fn (if protocol-fn)
       protocol (:protocol info)]

      (cond
        ;; :opt-not, optimizes to !thing, skipping call to (not thing)
        ;; FIXME: a cljs.core/not macro could do this? all the other stuff does? probably not done for legacy reasons?
        (and (= (:name info) 'cljs.core/not) (= 1 argc))
        (let [arg-tag (ana/infer-tag enve (first args-exprs))]
          (if (= 'boolean arg-tag)
            (make-invoke :opt-not env form fexpr args-exprs)
            ;; regular cljs.core/not call
            (make-invoke :fn env form fexpr args-exprs)))

        ;; :opt-count, optimized call to .length for strings/arrays, skipping actual (count ...)
        (and (= (:name info) 'cljs.core/count)
             (let [arg-tag (ana/infer-tag enve (first args-exprs))]
               (contains? '#{string array} arg-tag)))
        (make-invoke :opt-count env form fexpr args-exprs)

        ;; (:foo bar), use optimized ifn invoke
        (or (keyword? f) (= 'cljs.core/Keyword ftag))
        (do (when-not (or (== 1 argc) (== 2 argc))
              (ana/warning :fn-arity env {:name f :argc argc}))
            ;; FIXME: check first arg for defrecord field access opt
            (let [arg-expr (first args-exprs)
                  arg-tag (ana/infer-tag enve arg-expr)
                  field-sym (symbol (name f))]

              (if (and (and (keyword? f) (nil? (namespace f)))
                       (== 1 argc)
                       (record-with-field? arg-tag field-sym))
                ;; emit optimized (. thing -foo) for (:foo thing) if thing is a known record with foo field
                (ana/analyze env
                  (with-meta
                    (list '. (first args) (symbol (str "-" (name f))))
                    (meta form)))

                ;; kw.ifn1(foo)
                (make-invoke :ifn env form fexpr args-exprs))))

        ;; f is a protocol-fn, figure out if it can be invoke directly or through dispatch fn
        protocol
        ;; FIXME: throw proper error
        (do (assert (pos? argc) "protocol functions require at least one argument")
            (let [arg-expr (first args-exprs)
                  arg-tag (ana/infer-tag enve arg-expr)

                  use-direct-invoke?
                  (and arg-tag
                       ;; dunno why its in env but defprotocol sets it to make the helper fns it creates
                       (or ana/*cljs-static-fns* (:protocol-inline env))
                       (or (= arg-tag 'not-native)
                           (= protocol arg-tag)
                           ;; ignore new type hints for now - David
                           (and (not (set? arg-tag))
                                (not ('#{any clj clj-or-nil clj-nil number string boolean function object array js} arg-tag))
                                (when-let [ps (:protocols (ana/resolve-existing-var env arg-tag))]
                                  (contains? ps protocol)))))]

              ;; FIXME: validate argc to match protocol-fn

              (if use-direct-invoke?
                {:op :invoke
                 :env env
                 :form form
                 :fn fexpr
                 :invoke-type :protocol
                 :protocol-name protocol
                 :protocol-fn (:name info)
                 :args args-exprs
                 :children [:fn :args]}

                ;; invoke protocol dispatch directly, which is always a function
                (make-invoke :fn env form fexpr args-exprs))))

        ;; optional/dangerous optimization!
        ;; (thing foo bar) when thing is tagged not-native, invoke as IFn directly
        ;; breaks code if tagged incorrectly but bypasses property check
        (= 'not-native ftag)
        (make-invoke :ifn env form fexpr args-exprs)

        ;; cases where (thing foo bar) should be invoked directly as thing(foo, bar)
        ;; without going through further CLJS related checks for variadic/IFn support
        (or (ana/js-tag? ftag)
            (= 'function ftag)
            (:foreign info)
            (let [ns (:ns info)]
              ;; thing resolved to fully qualified ns, invoke as function if no analyzer data is found for ns
              ;; likely means JS namespaces (eg. goog.string, ...)
              (or (= 'js ns)
                  (= 'Math ns)
                  (and ns (not (contains? (::ana/namespaces @env/*compiler*) ns))))))
        (make-invoke :fn env form fexpr args-exprs)

        ;; no further optimizations for development code, always go through .call
        ;; FIXME: should still check arity
        ;; (def ^:dynamic *thing* ...) should go through .call always
        (or (not ana/*cljs-static-fns*)
            (:dynamic info))
        (make-invoke :dot-call env form fexpr args-exprs)

        ;; (defn thing ...) might be variadic or have multiple arities
        (:fn-var info)
        (let [variadic? (:variadic? info)
              mps (:method-params info)
              mfa (:max-fixed-arity info)]
          (cond
            ;; if only one method, invoke directly, always a function
            (and (not variadic?) (= (count mps) 1))
            (make-invoke :fn env form fexpr args-exprs)

            ;; direct dispatch to variadic case
            (and variadic? (> argc mfa))
            {:op :invoke
             :env env
             :form form
             :fn (update-in fexpr [:info]
                   (fn [info]
                     (-> info
                         ;; FIXME: illegal for analyzer to call compiler fn
                         ;; not sure why this is needed in the first place?
                         (assoc :name (symbol (str (comp/munge info))))
                         ;; bypass local fn-self-name munging, we're emitting direct
                         ;; shadowing already applied
                         (update-in [:info]
                           #(-> % (dissoc :shadow) (dissoc :fn-self-name))))))
             :invoke-type :variadic-invoke
             :max-fixed-arity mfa
             :args args-exprs
             :children [:fn :args]}

            ;; direct dispatch to specific arity case
            (has-required-arity? mps argc)
            (make-invoke :ifn env form
              (update-in fexpr [:info]
                (fn [info]
                  (-> info
                      (assoc :name (symbol (str (comp/munge info))))
                      ;; bypass local fn-self-name munging, we're emitting direct
                      ;; shadowing already applied
                      (update-in [:info]
                        #(-> % (dissoc :shadow) (dissoc :fn-self-name))))))
              args-exprs)

            ;; dispatch to variadic helper fn
            :else
            (make-invoke :fn env form fexpr args-exprs)))

        ;; fallback where something might be a function or IFn impl but we can't tell at compile time

        ;; want to avoid the IIFE it previously created when foo is any kind of expr
        ;; (thing (foo))
        ;; (function() { var x = foo(); return thing.ifn1 ? thing.ifn(x) : thing.call(null, x); })()

        ;; so it is now always
        ;; maybe_ifn1(thing, foo())

        ;; could still analyze args and inline that check when all args are values/symbols
        ;; since then they don't need to be bound and wrapped in IIFE
        ;; thing.ifn1 ? thing.ifn1(foo) : thing.call(null, foo)

        ;; TODO: check what JS engines prefer, might affect performance
        ;; code size is minimally better with maybe_ifn helpers
        :maybe-ifn
        (make-invoke :maybe-ifn env form fexpr args-exprs))))

  ;; gotta hate private vars sometimes
  (defn comma-sep [xs]
    (interpose "," xs))

  (defn protocol-prefix [psym]
    (str (-> (str psym)
             (.replace \. \$)
             (.replace \/ \$))
         "$"))

  (defmethod comp/emit* :invoke
    [{f :fn :keys [invoke-type args env] :as expr}]
    (comp/emit-wrap env
      (case invoke-type
        :opt-not
        (comp/emits "(!(" (first args) "))")

        :opt-count
        (comp/emits "((" (first args) ").length)")

        :protocol
        (let [protocol-name (:protocol-name expr)
              protocol-fn (:protocol-fn expr)
              pimpl (str (munge (protocol-prefix protocol-name))
                         (munge (name protocol-fn))
                         "$arity$" (count args))]
          (comp/emits (first args) "." pimpl "(" (comma-sep (cons "null" (rest args))) ")"))

        :variadic-invoke
        (let [mfa (:max-fixed-arity expr)]
          (when (= 'cljs.spec.test-test (get-in expr [:env :ns :name]))
            (?> expr :variadic-invoke))
          (comp/emits f ".cljs$core$IFn$_invoke$arity$variadic(" (comma-sep (take mfa args))
            (when-not (zero? mfa) ",")
            "cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["
            (comma-sep (drop mfa args)) "], 0))"))

        :ifn
        (comp/emits f ".cljs$core$IFn$_invoke$arity$" (count args) "(" (comma-sep args) ")")

        :fn
        (comp/emits f "(" (comma-sep args) ")")

        :maybe-ifn
        (if (seq args)
          (comp/emits "shadow.cljs_helpers.maybe_ifn" (count args) (when ana/*fn-invoke-direct* "_direct") "(" f ", " (comma-sep args) ")")
          (comp/emits "shadow.cljs_helpers.maybe_ifn0" (when ana/*fn-invoke-direct* "_direct") "(" f ")"))

        :dot-call
        (comp/emits f ".call(" (comma-sep (cons "null" args)) ")")))))

(defn install-hacks! []
  ;; cljs.analyzer tweaks
  (replace-fn! #'ana/load-core shadow-load-core)
  (replace-fn! #'ana/resolve-var shadow-resolve-var)
  (replace-fn! #'ana/all-values? shadow-all-values?)
  #_ (replace-fn! #'ana/parse-invoke* shadow-parse-invoke*)

  ;; cljs.compiler tweaks
  (replace-fn! #'comp/emits shadow-emits)
  (replace-fn! #'comp/emitln shadow-emitln)

  (replace-fn! #'comp/find-ns-starts-with shadow-find-ns-starts-with)

  (replace-fn! #'cljs.core/goog-define goog-define)

  ;; remove these for now, not worth the trouble
  ;; (replace-fn! #'test/deftest @#'shadow-deftest)
  ;; (replace-fn! #'test/use-fixtures @#'shadow-use-fixtures)
  )


(in-ns 'cljs.compiler)

;; FIXME: patch this in CLJS. its the only externs inference call I can't work around
;; cljs will blindly generate (set! (.. Thing -prototype -something) ...) for
;; (deftype Thing []
;;   Object
;;   (something [...] ...))
;; but doesn't keep any kind of meta that something is being defined on Object
;; and that we should generate externs for it
(in-ns 'cljs.core)

;; multimethod in core, although I don't see how this is ever going to go past 2 impls?
;; also added the metadata for easier externs inference
;; switched .. to nested . since .. looses form meta
(defn- extend-prefix [tsym sym]
  (let [prop-sym (to-property sym)]
    (core/case (core/-> tsym meta :extend)
      :instance
      `(. ~tsym ~prop-sym)
      ;; :default
      `(. (. ~tsym ~'-prototype) ~prop-sym))))

(core/defn- add-obj-methods [type type-sym sigs]
  (map (core/fn [[f & meths :as form]]
         (core/let [[f meths] (if (vector? (first meths))
                                [f [(rest form)]]
                                [f meths])]
           `(set! ~(with-meta
                     (extend-prefix type-sym f)
                     {:shadow/object-fn f})
              ~(with-meta `(fn ~@(map #(adapt-obj-params type %) meths)) (meta form)))))
    sigs))


;; CLJS-3003
(core/defn- add-ifn-methods [type type-sym [f & meths :as form]]
  (core/let [this-sym (with-meta 'self__ {:tag type})
             argsym (gensym "args")

             ;; we are emulating JS .call where the first argument will become this inside the fn
             ;; but this is not actually what we want when emulating IFn since we need "this"
             ;; so the first arg is always dropped instead and we dispatch to the actual protocol fns
             call-fn
             `(fn [unused#]
                (this-as ~this-sym
                  (case (-> (js-arguments) (alength) (dec))
                    ~@(reduce
                        (core/fn [form [args & body]]
                          (core/let [arity (core/-> args (count) (core/dec))]
                            (conj
                              form
                              arity
                              (concat
                                (core/list
                                  (symbol (core/str ".cljs$core$IFn$_invoke$arity$" arity))
                                  this-sym)
                                (core/for [ar (range arity)]
                                  `(aget (js-arguments) ~(core/inc ar)))))))
                        []
                        meths)
                    (throw (js/Error. (str "Invalid arity: " (-> (js-arguments) (alength) (dec)))))
                    )))]

    (concat
      [`(set! ~(extend-prefix type-sym 'call) ~(with-meta call-fn (meta form)))
       `(set! ~(extend-prefix type-sym 'apply)
          ~(with-meta
             `(fn ~[this-sym argsym]
                (this-as ~this-sym
                  (.apply (.-call ~this-sym) ~this-sym
                    (.concat (array ~this-sym) (cljs.core/aclone ~argsym)))))
             (meta form)))]
      (ifn-invoke-methods type type-sym form))))

;; not a super critical issue but foo may resolve to js/module$foo...
;; which ends up emitting a bad code check
;; if((typeof js !== 'undefined') && (typeof js.module$foo !== 'undefined')){
;; temp fix till I can sort it out in resolve-var directly
(core/defmacro exists?
  "Return true if argument exists, analogous to usage of typeof operator
   in JavaScript."
  [x]
  (if (core/symbol? x)
    (core/let [resolved (:name (cljs.analyzer/resolve-var &env x))
               y (core/cond-> resolved
                   (= "js" (namespace resolved)) name)
               segs (string/split (core/str (string/replace (core/str y) "/" ".")) #"\.")
               n (count segs)
               syms (map
                      #(vary-meta (symbol "js" (string/join "." %))
                         assoc :cljs.analyzer/no-resolve true)
                      (reverse (take n (iterate butlast segs))))
               js (string/join " && " (repeat n "(typeof ~{} !== 'undefined')"))]
      (bool-expr (concat (core/list 'js* js) syms)))
    `(some? ~x)))

(comment

  (core/defmacro defprotocol
    "A protocol is a named set of named methods and their signatures:

    (defprotocol AProtocolName
      ;optional doc string
      \"A doc string for AProtocol abstraction\"

    ;method signatures
      (bar [this a b] \"bar docs\")
      (baz [this a] [this a b] [this a b c] \"baz docs\"))

    No implementations are provided. Docs can be specified for the
    protocol overall and for each method. The above yields a set of
    polymorphic functions and a protocol object. All are
    namespace-qualified by the ns enclosing the definition The resulting
    functions dispatch on the type of their first argument, which is
    required and corresponds to the implicit target object ('this' in
    JavaScript parlance). defprotocol is dynamic, has no special compile-time
    effect, and defines no new types.

    (defprotocol P
      (foo [this])
      (bar-me [this] [this y]))

    (deftype Foo [a b c]
      P
      (foo [this] a)
      (bar-me [this] b)
      (bar-me [this y] (+ c y)))

    (bar-me (Foo. 1 2 3) 42)
    => 45

    (foo
      (let [x 42]
        (reify P
          (foo [this] 17)
          (bar-me [this] x)
          (bar-me [this y] x))))
    => 17"
    [psym & doc+methods]
    (core/let [p (:name (cljs.analyzer/resolve-var (dissoc &env :locals) psym))
               [opts methods]
               (core/loop [opts {:protocol-symbol true}
                           methods []
                           sigs doc+methods]
                 (core/if-not (seq sigs)
                   [opts methods]
                   (core/let [[head & tail] sigs]
                     (core/cond
                       (core/string? head)
                       (recur (assoc opts :doc head) methods tail)
                       (core/keyword? head)
                       (recur (assoc opts head (first tail)) methods (rest tail))
                       (core/list? head)
                       (recur opts (conj methods head) tail)
                       :else
                       (throw #?(:clj  (Exception.
                                         (core/str "Invalid protocol, " psym " received unexpected argument"))
                                 :cljs (js/Error.
                                         (core/str "Invalid protocol, " psym " received unexpected argument"))))
                       ))))
               psym (vary-meta psym merge opts)
               ns-name (core/-> &env :ns :name)
               fqn (core/fn [n] (symbol (core/str ns-name) (core/str n)))
               prefix (protocol-prefix p)
               _ (core/doseq [[mname & arities] methods]
                   (core/when (some #{0} (map count (filter vector? arities)))
                     (throw
                       #?(:clj  (Exception.
                                  (core/str "Invalid protocol, " psym
                                            " defines method " mname " with arity 0"))
                          :cljs (js/Error.
                                  (core/str "Invalid protocol, " psym
                                            " defines method " mname " with arity 0"))))))
               expand-sig (core/fn [fname slot sig]
                            (core/let [sig (core/if-not (every? core/symbol? sig)
                                             (mapv (core/fn [arg]
                                                     (core/cond
                                                       (core/symbol? arg) arg
                                                       (core/and (map? arg) (core/some? (:as arg))) (:as arg)
                                                       :else (gensym))) sig)
                                             sig)

                                       fqn-fname (fqn fname)
                                       fsig (first sig)

                                       impl-sym (with-meta (gensym "impl_fn") {:tag 'function})

                                       ;; last check protocol on js null,string,function,array,object or default
                                       ;; this is identical for all impls so handled in a helper fn
                                       ;; default impls are registered via (goog.object/set protocol-fn "string" (fn [...] ...))
                                       ;; invoke still happens here since the arity varies
                                       check
                                       `(let [~impl-sym (js/shadow.cljs_helpers.find_protocol_impl ~fqn-fname ~fsig)]
                                          (if-not (nil? ~impl-sym)
                                            (~impl-sym ~@sig)
                                            (throw
                                              (missing-protocol
                                                ~(core/str psym "." fname) ~fsig))))

                                       ;; FIXME: this should actually be the first check, but that requires rewriting direct protocol invoke too
                                       ;; then check protocol fn in metadata (only when protocol is marked with :extend-via-metadata true)
                                       check
                                       (core/if-not (:extend-via-metadata opts)
                                         check
                                         `(if-let [meta-impl# (-> ~fsig (core/meta) (core/get '~fqn-fname))]
                                            ;; FIXME: this will go through IFn property check, can this even be IFn? likely only function?
                                            (meta-impl# ~@sig)
                                            ~check))

                                       ;; check protocol property on object (first check actually executed)
                                       check
                                       `(if (and (not (nil? ~fsig))
                                                 (not (nil? (. ~fsig ~(symbol (core/str "-" slot)))))) ;; Property access needed here.
                                          (. ~fsig ~slot ~@sig)
                                          ~check)]
                              `(~sig ~check)))
               psym (core/-> psym
                             (vary-meta update-in [:jsdoc] conj "@interface")
                             (vary-meta assoc-in [:protocol-info :methods]
                               (into {}
                                 (map
                                   (core/fn [[fname & sigs]]
                                     (core/let [doc (core/as-> (last sigs) doc
                                                      (core/when (core/string? doc) doc))
                                                sigs (take-while vector? sigs)]
                                       [(vary-meta fname assoc :doc doc)
                                        (vec sigs)]))
                                   methods)))
                             ;; for compatibility with Clojure
                             (vary-meta assoc-in [:sigs]
                               (into {}
                                 (map
                                   (core/fn [[fname & sigs]]
                                     (core/let [doc (core/as-> (last sigs) doc
                                                      (core/when (core/string? doc) doc))
                                                sigs (take-while vector? sigs)]
                                       [(keyword fname) {:name fname :arglists (list* sigs) :doc doc}]))
                                   methods))))
               method (core/fn [[fname & sigs]]
                        (core/let [doc (core/as-> (last sigs) doc
                                         (core/when (core/string? doc) doc))
                                   sigs (take-while vector? sigs)
                                   amp (core/when (some #{'&} (apply concat sigs))
                                         (cljs.analyzer/warning
                                           :protocol-with-variadic-method
                                           &env {:protocol psym :name fname}))
                                   _ (core/when-some [existing (core/get (-> &env :ns :defs) fname)]
                                       (core/when-not (= p (:protocol existing))
                                         (cljs.analyzer/warning
                                           :protocol-with-overwriting-method
                                           {} {:protocol psym :name fname :existing existing})))
                                   slot (symbol (core/str prefix (munge (name fname))))
                                   fname (vary-meta fname assoc
                                           :protocol p
                                           :doc doc)]
                          `(defn ~fname
                             ~@(map (core/fn [sig]
                                      (expand-sig fname
                                        (symbol (core/str slot "$arity$" (count sig)))
                                        sig))
                                 sigs))))]
      `(do
         (set! ~'*unchecked-if* true)
         (def ~psym (~'js* "function(){}"))
         ~@(map method methods)
         (set! ~'*unchecked-if* false)))))
