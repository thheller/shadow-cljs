(ns shadow.build.cljs-hacks
  (:require
    [cljs.analyzer :as ana]
    [cljs.compiler :as comp]
    [cljs.env :as env]
    [clojure.string :as str]
    [cljs.core]
    [cljs.test]))

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
                (str/starts-with? prop "cljs$"))
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
      (shadow.build.cljs-hacks/shadow-js-access-property current-ns prop))

    {:name qname
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
    {:name (symbol (str ns) (str sym))
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
    {:name (symbol (str ns) (str sym))
     :ns ns}

    :else
    (resolve-cljs-var ns sym)
    ))

(defn invokeable-ns?
  "Returns true if ns is a required namespace and a JavaScript module that
   might be invokeable as a function."
  [alias env]
  (when-let [ns (ana/resolve-ns-alias env alias nil)]
    (or (js-module-exists? ns)
        (is-shadow-shim? ns)
        )))

(defn resolve-invokeable-ns [alias current-ns env]
  (let [ns (ana/resolve-ns-alias env alias)]
    {:name (symbol "js" (str ns))
     :tag 'js
     :ret-tag 'js
     :ns 'js}))

(def known-safe-js-globals
  "symbols known to be closureJS compliant namespaces"
  #{"cljs"
    "goog"
    "console"})

(defn shadow-resolve-var
  "Resolve a var. Accepts a side-effecting confirm fn for producing
   warnings about unresolved vars."
  [env sym confirm]
  (let [locals (:locals env)
        current-ns (-> env :ns :name)
        sym-ns-str (namespace sym)]

    (if (= "js" sym-ns-str)
      (do (when (contains? locals (-> sym name symbol))
            (ana/warning :js-shadowed-by-local env {:name sym}))

          ;; always record all fully qualified js/foo.bar calls
          ;; except for the code emitted by cljs.core/exists?
          ;; (exists? some.foo/bar)
          ;; checks js/some, js/some.foo, js/some.foo.bar
          (when-not (-> sym meta ::ana/no-resolve)
            (let [[global & props]
                  (str/split (name sym) #"\.")]

              ;; do not record access to
              ;; js/goog.string.format
              ;; js/cljs.core.assoc
              ;; just in case someone does that, we won't need externs for those
              (when-not (contains? known-safe-js-globals global)
                (shadow-js-access-global current-ns global)
                (when (seq props)
                  (doseq [prop props]
                    (shadow-js-access-property current-ns prop))))))

          {:name sym
           :ns 'js
           :tag 'js
           :ret-tag 'js})

      (let [s (str sym)
            lb (get locals sym)
            current-ns-info (ana/gets @env/*compiler* ::ana/namespaces current-ns)]

        (cond
          (some? lb) lb

          (some? sym-ns-str)
          (let [ns sym-ns-str
                ns (symbol (if (= "clojure.core" ns) "cljs.core" ns))
                ;; thheller: remove the or
                full-ns (ana/resolve-ns-alias env ns (symbol ns))
                ;; strip ns
                sym (symbol (name sym))]
            (when (some? confirm)
              (when (not= current-ns full-ns)
                (ana/confirm-ns env full-ns))
              (confirm env full-ns sym))
            (resolve-ns-var full-ns sym current-ns))

          ;; FIXME: would this not be better handled if checked before calling resolve-var
          ;; and analyzing this properly?
          (ana/dotted-symbol? sym)
          (let [idx (.indexOf s ".")
                prefix (symbol (subs s 0 idx))
                suffix (subs s (inc idx))]
            (if-some [lb (get locals prefix)]
              {:name (symbol (str (:name lb)) suffix)}
              (if-some [full-ns (ana/gets current-ns-info :imports prefix)]
                {:name (symbol (str full-ns) suffix)}
                (if-some [info (ana/gets current-ns-info :defs prefix)]
                  (merge info
                    {:name (symbol (str current-ns) (str sym))
                     :ns current-ns})
                  (merge (ana/gets @env/*compiler* ::ana/namespaces prefix :defs (symbol suffix))
                    {:name (if (= "" prefix) (symbol suffix) (symbol (str prefix) suffix))
                     :ns prefix})))))

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
          '{:name cljs.core/Object
            :ns cljs.core}

          (ana/core-name? env sym)
          (do (when (some? confirm)
                (confirm env 'cljs.core sym))
              (resolve-cljs-var 'cljs.core sym))

          (invokeable-ns? s env)
          (resolve-invokeable-ns s current-ns env)

          :else
          (do (when (some? confirm)
                (confirm env current-ns sym))
              (resolve-cljs-var current-ns sym)
              ))))))

(defn infer-externs-dot
  [{:keys [form form-meta method field target-tag env prop tag] :as ast}
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
                   ;; set in cljs.core/extend-prefix hack below
                   (not (some-> prop meta :shadow/protocol-prop))
                   (not shadow-object-fn)
                   ;; no immediate ideas how to annotate these so it doesn't warn
                   (not= form '(. cljs.core/List -EMPTY))
                   (not= form '(. cljs.core/PersistentVector -EMPTY-NODE)))

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
        {:op :dot
         :env env
         :form form
         :form-meta form-meta
         :target targetexpr
         :target-tag target-tag
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
                argexprs (map #(ana/analyze enve %) args)
                children (into [targetexpr] argexprs)]
            (assoc ast :prop method :method method :args argexprs :children children))

          ;; (. thing -foo 1 2 3)
          (and prop-access? (seq member+))
          (throw (ex-info "dot prop access with args" {:form form}))

          ;; (. thing -foo)
          prop-access?
          (let [children [targetexpr]]
            (assoc ast :prop member :field (-> member (name) (subs 1) (symbol)) :children children))

          ;; (. thing foo)
          ;; (. thing foo 1 2 3)
          member-sym?
          (let [argexprs (map #(ana/analyze enve %) member+)
                children (into [targetexpr] argexprs)]
            (assoc ast :prop member :method member :args argexprs :children children))

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

(defmethod comp/emit* :var
  [{:keys [info env form] :as ast}]
  (if-let [const-expr (:const-expr ast)]
    (comp/emit (assoc const-expr :env env))
    (let [{:keys [options] :as cenv} @env/*compiler*
          var-name (:name info)]

      ;; We need a way to write bindings out to source maps and javascript
      ;; without getting wrapped in an emit-wrap calls, otherwise we get
      ;; e.g. (function greet(return x, return y) {}).
      (cond
        ;; Emit the arg map so shadowing is properly handled when munging
        ;; (prevents duplicate fn-param-names)
        (:binding-form? ast)
        (comp/emits (comp/munge ast))

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
                (and (= "default" part)
                     (es5>= (:language-out options)))
                (comp/emits ".default")

                (and (contains? ana/js-reserved part)
                     (not (-> var-name meta ::ana/no-resolve)))
                (comp/emits "[\"" part "\"]")

                :else
                (comp/emits "." (comp/munge part))))))

        :else
        (when-not (= :statement (:context env))
          (comp/emit-wrap env
            (comp/emits
              (cond-> info
                (not= form 'js/-Infinity)
                (comp/munge)))))))))

(in-ns 'cljs.analyzer)

;; noop load-core since its called a bajillion times otherwise and should only be called once
;; also fixes the race condition in load-core
(defn load-core [])

(defn resolve-var
  ([env sym]
   (shadow.build.cljs-hacks/shadow-resolve-var env sym nil))
  ([env sym confirm]
   (shadow.build.cljs-hacks/shadow-resolve-var env sym confirm)))

(in-ns 'cljs.compiler)

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
    (do (when-not (nil? *source-map-data*)
          (swap! *source-map-data* source-map-inc-col (count x)))
        (print x))
    #?(:clj (map? x) :cljs (ana/cljs-map? x)) (emit x)
    #?(:clj (seq? x) :cljs (ana/cljs-seq? x)) (run! emit1 x)
    #?(:clj (fn? x) :cljs ^boolean (goog/isFunction x)) (x)
    :else (let [s (print-str x)]
            (when-not (nil? *source-map-data*)
              (swap! *source-map-data* source-map-inc-col (count s)))
            (print s))))

(defn emits [& xs]
  (run! emit1 xs))

(defn emitln [& xs]
  (run! emit1 xs)
  (newline)
  (when *source-map-data*
    (swap! *source-map-data* source-map-inc-line))
  nil)

(in-ns 'cljs.core)

(defn shadow-mark-protocol-prop [sym]
  (with-meta sym {:shadow/protocol-prop true}))

;; multimethod in core, although I don't see how this is ever going to go past 2 impls?
;; also added the metadata for easier externs inference
;; switched .. to nested . since .. looses form meta
(defn- extend-prefix [tsym sym]
  (let [prop-sym
        (-> (to-property sym)
            (cond->
              ;; exploiting a "flaw" where extend-prefix is called with a string
              ;; instead of a symbol for protocol impls
              ;; adding the extra meta so we can use it for smarter externs inference
              (core/string? sym)
              (shadow-mark-protocol-prop)
              ))]

    (core/case (core/-> tsym meta :extend)
      :instance
      `(. ~tsym ~prop-sym)
      ;; :default
      `(. (. ~tsym ~'-prototype) ~prop-sym))))


(core/defmacro implements?
  "EXPERIMENTAL"
  [psym x]
  (core/let [p (:name (cljs.analyzer/resolve-var (dissoc &env :locals) psym))
             prefix (protocol-prefix p)
             ;; thheller: ensure things are tagged so externs inference knows this is a protocol prop
             protocol-prop (shadow-mark-protocol-prop (symbol (core/str "-" prefix)))
             xsym (bool-expr (gensym))
             [part bit] (fast-path-protocols p)
             msym (symbol
                    (core/str "-cljs$lang$protocol_mask$partition" part "$"))]
    (core/if-not (core/symbol? x)
      `(let [~xsym ~x]
         (if ~xsym
           (if (or ~(if bit `(unsafe-bit-and (. ~xsym ~msym) ~bit) false)
                   (identical? cljs.core/PROTOCOL_SENTINEL (. ~xsym ~protocol-prop)))
             true
             false)
           false))
      `(if-not (nil? ~x)
         (if (or ~(if bit `(unsafe-bit-and (. ~x ~msym) ~bit) false)
                 (identical? cljs.core/PROTOCOL_SENTINEL (. ~x ~protocol-prop)))
           true
           false)
         false))))

(core/defmacro satisfies?
  "Returns true if x satisfies the protocol"
  [psym x]
  (core/let [p (:name
                 (cljs.analyzer/resolve-var
                   (dissoc &env :locals) psym))
             prefix (protocol-prefix p)
             ;; thheller: ensure things are tagged so externs inference knows this is a protocol prop
             protocol-prop (shadow-mark-protocol-prop (symbol (core/str "-" prefix)))
             xsym (bool-expr (gensym))
             [part bit] (fast-path-protocols p)
             msym (symbol
                    (core/str "-cljs$lang$protocol_mask$partition" part "$"))]
    (core/if-not (core/symbol? x)
      `(let [~xsym ~x]
         (if-not (nil? ~xsym)
           (if (or ~(if bit `(unsafe-bit-and (. ~xsym ~msym) ~bit) false)
                   (identical? cljs.core/PROTOCOL_SENTINEL (. ~xsym ~protocol-prop)))
             true
             (if (coercive-not (. ~xsym ~msym))
               (cljs.core/native-satisfies? ~psym ~xsym)
               false))
           (cljs.core/native-satisfies? ~psym ~xsym)))
      `(if-not (nil? ~x)
         (if (or ~(if bit `(unsafe-bit-and (. ~x ~msym) ~bit) false)
                 (identical? cljs.core/PROTOCOL_SENTINEL (. ~x ~protocol-prop)))
           true
           (if (coercive-not (. ~x ~msym))
             (cljs.core/native-satisfies? ~psym ~x)
             false))
         (cljs.core/native-satisfies? ~psym ~x)))))


(core/defmacro case
  "Takes an expression, and a set of clauses.

  Each clause can take the form of either:

  test-constant result-expr

  (test-constant1 ... test-constantN)  result-expr

  The test-constants are not evaluated. They must be compile-time
  literals, and need not be quoted.  If the expression is equal to a
  test-constant, the corresponding result-expr is returned. A single
  default expression can follow the clauses, and its value will be
  returned if no clause matches. If no default expression is provided
  and no clause matches, an Error is thrown.

  Unlike cond and condp, case does a constant-time dispatch, the
  clauses are not considered sequentially.  All manner of constant
  expressions are acceptable in case, including numbers, strings,
  symbols, keywords, and (ClojureScript) composites thereof. Note that since
  lists are used to group multiple constants that map to the same
  expression, a vector can be used to match a list if needed. The
  test-constants need not be all of the same type."
  [e & clauses]
  (core/let [esym (gensym)
             default (if (odd? (count clauses))
                       (last clauses)
                       `(throw
                          (js/Error.
                            (cljs.core/str "No matching clause: " ~esym))))
             env &env
             pairs (reduce
                     (core/fn [m [test expr]]
                       (core/cond
                         (seq? test)
                         (reduce
                           (core/fn [m test]
                             (core/let [test (if (core/symbol? test)
                                               (core/list 'quote test)
                                               test)]
                               (assoc-test m test expr env)))
                           m test)
                         (core/symbol? test)
                         (assoc-test m (core/list 'quote test) expr env)
                         :else
                         (assoc-test m test expr env)))
                     {} (partition 2 clauses))
             tests (keys pairs)]
    (core/cond
      (every? (some-fn core/number? core/string? #?(:clj core/char? :cljs (core/fnil core/char? :nonchar)) #(const? env %)) tests)
      (core/let [no-default (if (odd? (count clauses)) (butlast clauses) clauses)
                 tests (mapv #(if (seq? %) (vec %) [%]) (take-nth 2 no-default))
                 thens (vec (take-nth 2 (drop 1 no-default)))]
        `(let [~esym ~e] (case* ~esym ~tests ~thens ~default)))

      (every? core/keyword? tests)
      (core/let [no-default (if (odd? (count clauses)) (butlast clauses) clauses)
                 kw-str #(.substring (core/str %) 1)
                 tests (mapv #(if (seq? %) (mapv kw-str %) [(kw-str %)]) (take-nth 2 no-default))
                 thens (vec (take-nth 2 (drop 1 no-default)))]
        `(let [~esym ~e
               ;; thheller: added clj tag
               ~esym (if (keyword? ~esym) ^clj (.-fqn ~esym) nil)]
           (case* ~esym ~tests ~thens ~default)))

      ;; equality
      :else
      `(let [~esym ~e]
         (cond
           ~@(mapcat (core/fn [[m c]] `((cljs.core/= ~m ~esym) ~c)) pairs)
           :else ~default)))))

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

(in-ns 'cljs.test)

;; why does it go into the :test metadata?
;; there are far too many macros in cljs.test
;; instead of relying on the analyzer data all tests are registered at runtime
;; so they can be handled more dynamically and don't rely on the macro being recompiled
(defmacro deftest
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

(defmacro use-fixtures [type & fns]
  {:pre [(contains? #{:once :each} type)]}
  `(shadow.test.env/register-fixtures (quote ~(symbol (str *ns*))) ~type [~@fns]))