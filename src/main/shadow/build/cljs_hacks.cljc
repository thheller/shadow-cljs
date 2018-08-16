(ns shadow.build.cljs-hacks
  (:require
    [clojure.string :as str]
    [cljs.analyzer :as ana]
    [cljs.compiler :as comp]
    [cljs.env :as env]
    [cljs.core :as core]
    [cljs.test :as test]
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
  ([env sym]
   (shadow-resolve-var env sym nil))
  ([env sym confirm]
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
               )))))))

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
                   (not (contains? (:shadow/protocol-prefixes @env/*compiler*) sprop))
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


(defn replace-fn! [the-var the-fn]
  (when (not= @the-var the-fn)
    (log/debug ::replace-fn! {:the-var the-var})
    (alter-var-root the-var (fn [_] the-fn))))

(defn install-hacks! []
  ;; cljs.analyzer tweaks
  (replace-fn! #'ana/load-core shadow-load-core)
  (replace-fn! #'ana/resolve-var shadow-resolve-var)

  ;; cljs.compiler tweaks
  (replace-fn! #'comp/emits shadow-emits)
  (replace-fn! #'comp/emitln shadow-emitln)

  (replace-fn! #'test/deftest @#'shadow-deftest)
  (replace-fn! #'test/use-fixtures @#'shadow-use-fixtures))

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