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
  (or (str/starts-with? (str ns) "shadow.js.shim")
      ;; :js-provider :import - dev
      (str/starts-with? (str ns) "shadow.esm.esm_import$")
      ;; :js-provider :import - release
      (str/starts-with? (str ns) "esm_import$")))

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

(defn goog-module-dep? [sym]
  (let [compiler-env @env/*compiler*]
    (when (contains? (:goog-modules compiler-env) sym)
      (or (not (:global-goog-object&array (:options compiler-env)))
          (not (contains? '#{goog.object goog.array} sym)))
      )))

(defn resolve-ns-var [ns sym current-ns]
  (let [compiler-env @env/*compiler*]
    (cond
      ;; must ensure that CLJS vars resolve first
      (contains? (:cljs.analyzer/namespaces compiler-env) ns)
      (resolve-cljs-var ns sym)

      (or (js-module-exists? ns)
          (is-shadow-shim? ns))
      (resolve-js-var ns sym current-ns)

      (goog-module-dep? ns)
      {:op :js-var
       :name (symbol (str (ana/munge-goog-module-lib current-ns ns) "." sym))
       :ns ns}

      (contains? (:goog-names compiler-env) ns)
      {:op :js-var
       :name (symbol (str ns) (str sym))
       :ns ns}

      :else
      (resolve-cljs-var ns sym)
      )))

;; ana/resolve-ns-alias converts to symbols too much, we already only have symbols
(defn resolve-ns-alias [env alias]
  (or (ana/gets env :ns :requires alias)
      (ana/gets env :ns :as-aliases alias)))

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
   (shadow-resolve-var env sym confirm true))
  ([env sym confirm default?]
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

           (some? (ana/gets current-ns-info :require-global sym))
           (let [global-sym (ana/gets current-ns-info :require-global sym)]
             {:op :js-var
              :name (symbol "js" (str global-sym))
              :ns 'js})

           (some? (ana/gets current-ns-info :rename-global sym))
           (let [[global-sym prop] (ana/gets current-ns-info :rename-global sym)]
             {:op :js-var
              :name (symbol "js" (str global-sym "." prop))
              :ns 'js})

           (some? (ana/gets current-ns-info :use-global sym))
           (let [global-sym (ana/gets current-ns-info :use-global sym)]
             {:op :js-var
              :name (symbol "js" (str global-sym "." sym))
              :ns 'js})

           (some? (ana/gets current-ns-info :imports sym))
           (recur env (ana/gets current-ns-info :imports sym) confirm default?)

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

           (invokeable-ns? sym env)
           (resolve-invokeable-ns sym current-ns env)

           (ana/core-name? env sym)
           (do (when (some? confirm)
                 (confirm env 'cljs.core sym))
               (resolve-cljs-var 'cljs.core sym))

           ;; short circuit for direct references to a fully qualify closure name eg. goog.math.Integer
           ;; will end up here for anything import such as (:import [goog.module ModuleLoader])

           ;; goog.module special treatment
           (goog-module-dep? sym)
           {:op :js-var
            :goog-module true
            :name (symbol (ana/munge-goog-module-lib current-ns sym))
            :ns sym}

           ;; regular goog.provide ns
           (contains? (:goog-names @env/*compiler*) sym)
           {:op :js-var
            :name (symbol "js" s)
            :ns 'js}

           ;; any symbol with a dot but no namespace, already checked all renames/imports/requires
           ;; so that (:require ["some-js" :as foo.bar]) (foo.bar)
           ;; works properly and isn't desugared into anything else
           (ana/dotted-symbol? sym)
           (let [idx (.indexOf s ".")
                 prefix (subs s 0 idx)
                 prefix-sym (symbol prefix)
                 suffix (subs s (inc idx))]
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

                   ;; (:require [x :refer (Foo)]) + Foo.Bar uses
                   ;; :uses is populated as {Foo x} so we return x/Foo.Bar
                   (if-some [refer-from-ns (ana/gets current-ns-info :uses prefix-sym)]
                     {:op :var
                      :name (symbol (str refer-from-ns) (str sym))
                      :ns refer-from-ns}

                     ;; (:require [x :rename {Foo Bar}]) + Bar.X uses
                     ;; :renames is populated as {Bar x/Foo} so we return x/Foo.X
                     (if-some [renamed-sym (ana/gets current-ns-info :renames prefix-sym)]
                       {:op :var
                        :name (symbol (namespace renamed-sym) (str (name renamed-sym) "." suffix))
                        :ns (symbol (namespace renamed-sym))}


                       ;; special case for PersistentHashSet.createWithCheck where prefix is from cljs.core
                       ;; https://github.com/thheller/shadow-cljs/issues/1198
                       (if (ana/core-name? env prefix-sym)
                         {:op :var
                          :name (symbol "cljs.core" s)
                          :ns 'cljs.core}

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
                                       )))))))))))))

           :else
           (when default?
             (when (some? confirm)
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

;; {:keys [Thing]}
;; symbols destructured out of a map are tagged with '#{any clj-nil}
;; so they are the same as not tagged at all or just 'any
(defn any-tag? [tag]
  (or (nil? tag) (= 'any tag) (and (set? tag) (contains? tag 'any))))

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
                   ;; generally assume everything in cljs.* is safe
                   ;; cljs.core otherwise has a lot of inference warnings when used as local dep
                   (not (str/starts-with? (name (:name (:ns env))) "cljs."))
                   (not= "prototype" sprop)
                   (not= "constructor" sprop)
                   ;; defrecord
                   (not= "getBasis" sprop)
                   (not (str/starts-with? sprop "cljs$"))
                   (any-tag? tag)
                   (any-tag? target-tag)
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
                             (or (str/starts-with? (-> target :name name) "goog.")
                                 (:goog-module (:info target))))))

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
            sprop))

        ;; cases where extend-protocol is used for js-hinted types
        ;; (def Alias js/Foo)
        ;; (extend-protocol Foo Alias (foo [x] (.shouldNotRename x))
        ;; x will have target-tag that.ns/Alias, resolving to check if that is tagged as js
        (when (and (symbol? target-tag) (not (ana/js-tag? target-tag)))
          (when-let [{:keys [tag]} (shadow-resolve-var env target-tag nil false)]
            (when (ana/js-tag? tag)
              (shadow-js-access-property
                (-> env :ns :name)
                sprop
                ))))))))

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


;; its private in cljs.compiler ...
(def es5>=
  (into #{}
    (comp
      (mapcat (fn [lang]
                [lang (keyword (str/replace (name lang) #"^ecmascript" "es"))])))
    [:ecmascript5 :ecmascript5-strict :ecmascript6 :ecmascript6-strict
     :ecmascript-2015 :ecmascript6-typed :ecmascript-2016 :ecmascript-2017
     :ecmascript-next :ecmascript-next-in]))

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
               (assoc m
                 :jsdoc [(str "@define {" type "}\n@type {" type "}")]
                 :tag (symbol type))))
       (js/goog.define ~defname ~default))))

(defn shadow-defonce
  "defs name to have the root value of init iff the named var has no root value,
  else init is unevaluated"
  [&from &env x init]
  (if (= :release (:shadow.build/mode &env))
    ;; release builds will never overwrite a defonce, skip DCE-unfriendly verbose code
    `(def ~x ~init)
    `(when-not (cljs.core/exists? ~x)
       (def ~x ~init))))


;; reify impl using analyze-top, fixes https://clojure.atlassian.net/browse/CLJS-3207
;; where the closure compiler decides to move some protocol impls to separate modules
;; but with reify defining the class conditionally it may end up trying to restore the
;; stub methods before the class is defined.

;; affects spec and another known example is reitit

;; $APP.$reitit$core$t_reitit$0core609782$$.prototype.$reitit$core$Router$routes$arity$1$ = $JSCompiler_unstubMethod$$(5, function() {
;;  return this.$routes$;
;; });

(defn shadow-reify
  [&form &env & impls]

  ;; can't require directly due to circular dependency
  (let [at (find-var 'shadow.build.compiler/*analyze-top*)]
    (if (and at @at)

      ;; only use analyze-top if bound, just in case something else tries to compile
      ;; CLJS from outside shadow-cljs
      (let [t (with-meta
                (gensym (str "t_" (str/replace (str (munge ana/*cljs-ns*)) "." "$")))
                {:anonymous true})
            meta-sym (gensym "meta")
            this-sym (gensym "_")
            locals (keys (:locals &env))]

        (@at
          `(cljs.core/deftype ~t [~@locals ~meta-sym]
             cljs.core/IWithMeta
             (~'-with-meta [~this-sym ~meta-sym]
               (~'new ~t ~@locals ~meta-sym))
             cljs.core/IMeta
             (~'-meta [~this-sym] ~meta-sym)
             ~@impls))

        `(~'new ~t ~@locals ~(ana/elide-reader-meta (meta &form))))

      ;; default impl if shadow analyze-top is not bound
      (let [t (with-meta
                (gensym
                  (str "t_" (str/replace (str (munge ana/*cljs-ns*)) "." "$")))
                {:anonymous true})
            meta-sym (gensym "meta")
            this-sym (gensym "_")
            locals (keys (:locals &env))
            ns (-> &env :ns :name)]
        `(~'do
           (cljs.core/when-not (cljs.core/exists? ~(symbol (str ns) (str t)))
             (cljs.core/deftype ~t [~@locals ~meta-sym]
               cljs.core/IWithMeta
               (~'-with-meta [~this-sym ~meta-sym]
                 (~'new ~t ~@locals ~meta-sym))
               cljs.core/IMeta
               (~'-meta [~this-sym] ~meta-sym)
               ~@impls))
           (~'new ~t ~@locals ~(ana/elide-reader-meta (meta &form))))))))

;; https://github.com/clojure/clojurescript/commit/1589e5848ebb56ab451cb73f955dbc0b01e7aba0
;; oops, seem to have missed keyword?
(defn shadow-all-values? [exprs]
  (every? #(or (nil? %) (symbol? %) (string? %) (number? %) (keyword? %) (true? %) (false? %)) exprs))

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

(defn invalid-arity? [{variadic :variadic? :keys [max-fixed-arity method-params]} argc]
  (and (not (has-required-arity? method-params argc))
       (or (not variadic)
           (and variadic (< argc max-fixed-arity)))))

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
  (let [enve (assoc env :context :expr)
        fexpr (ana/analyze enve f)

        argc (count args)
        ftag (ana/infer-tag env fexpr)

        ;; delay parsing args
        ;; :maybe-ifn case may decide to wrap in which case args shouldn't be analyzed twice
        args-exprs (delay (mapv #(ana/analyze enve %) args))

        info (:info fexpr)
        ;; fully-qualified symbol name of protocol-fn (if protocol-fn)
        protocol (:protocol info)]

    (when (:fn-var info)
      (when (invalid-arity? (:info fexpr) argc)
        (ana/warning :fn-arity env {:name (:name info) :argc argc})))

    (let [deprecated? (-> fexpr :info :deprecated)
          no-warn? (-> form meta :deprecation-nowarn)]
      (when (and (boolean deprecated?)
                 (not (boolean no-warn?)))
        (ana/warning :fn-deprecated env {:fexpr fexpr})))

    (when (some? (-> fexpr :info :type))
      (ana/warning :invoke-ctor env {:fexpr fexpr}))

    (cond
      ;; prevent (Array/.push some-arr 1) from using Reflect.apply
      (and (= :qualified-method (:op fexpr))
           (= :method (:kind fexpr))
           (pos? (count args)))
      (let [[target-expr & args] @args-exprs]
        {:op :host-call
         :form form
         :env enve
         :children [:target :args]
         :target target-expr
         :method (:name fexpr)
         :args args})

      ;; :opt-not, optimizes to !thing, skipping call to (not thing)
      ;; FIXME: a cljs.core/not macro could do this? all the other stuff does? probably not done for legacy reasons?
      (and (= (:name info) 'cljs.core/not) (= 1 argc))
      (let [arg-tag (ana/infer-tag enve (first @args-exprs))]
        (if (= 'boolean arg-tag)
          (make-invoke :opt-not env form fexpr @args-exprs)
          ;; regular cljs.core/not call
          (make-invoke :fn env form fexpr @args-exprs)))

      ;; :opt-count, optimized call to .length for strings/arrays, skipping actual (count ...)
      (and (= (:name info) 'cljs.core/count)
           (let [arg-tag (ana/infer-tag enve (first @args-exprs))]
             (contains? '#{string array} arg-tag)))
      (make-invoke :opt-count env form fexpr @args-exprs)

      ;; (:foo bar), use optimized ifn invoke
      (or (keyword? f) (= 'cljs.core/Keyword ftag))
      (do (when-not (or (== 1 argc) (== 2 argc))
            (ana/warning :fn-arity env {:name f :argc argc}))
          ;; FIXME: check first arg for defrecord field access opt
          (let [arg-expr (first @args-exprs)
                arg-tag (ana/infer-tag enve arg-expr)]

            (if (and (and (keyword? f) (nil? (namespace f)))
                     (== 1 argc)
                     (let [field-sym (symbol (name f))]
                       (record-with-field? arg-tag field-sym)))
              ;; emit optimized (. thing -foo) for (:foo thing) if thing is a known record with foo field
              (ana/analyze env
                (with-meta
                  (list '. (first args) (symbol (str "-" (name f))))
                  (meta form)))

              ;; kw.ifn1(foo)
              (make-invoke :ifn env form fexpr @args-exprs))))

      ;; f is a protocol-fn, figure out if it can be invoke directly or through dispatch fn
      protocol
      ;; FIXME: throw proper error
      (do (assert (pos? argc) "protocol functions require at least one argument")
          (let [arg-expr (first @args-exprs)
                arg-tag (ana/infer-tag enve arg-expr)

                use-direct-invoke?
                (and arg-tag
                     ;; dunno why its in env but defprotocol sets it to make the helper fns it creates
                     (or ana/*cljs-static-fns* (:protocol-inline env))
                     (or (= arg-tag 'not-native)
                         (= protocol arg-tag)
                         ;; ignore new type hints for now - David
                         (and (not (set? arg-tag))
                              (not ('#{any clj clj-or-nil clj-nil number string boolean function object array js ignore} arg-tag))
                              (when-let [ps (:protocols (shadow-resolve-var env arg-tag))]
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
               :args @args-exprs
               :children [:fn :args]}

              ;; invoke protocol dispatch directly, which is always a function
              (make-invoke :fn env form fexpr @args-exprs))))

      ;; optional/dangerous optimization!
      ;; (thing foo bar) when thing is tagged not-native, invoke as IFn directly
      ;; breaks code if tagged incorrectly but bypasses property check
      (= 'not-native ftag)
      (make-invoke :ifn env form fexpr @args-exprs)

      ;; cases where (thing foo bar) should be invoked directly as thing(foo, bar)
      ;; without going through further CLJS related checks for variadic/IFn support
      (or (= 'function ftag)
          (:foreign info)
          (let [ns (:ns info)]
            ;; thing resolved to fully qualified ns, invoke as function if no analyzer data is found for ns
            ;; likely means JS namespaces (eg. goog.string, ...)
            (or (= 'js ns)
                (= 'Math ns)
                (and ns (not (contains? (::ana/namespaces @env/*compiler*) ns)))))
          ;; opt-in option to optimize function invoke for known JS types assumed to be functions
          ;; this breaks stuff when JS objects implement IFn but are ^js tagged
          ;; (defn do-something [^js thing]
          ;;   (thing "foo" "bar"))
          ;; will try to call thing as a regular function only when enabled
          ;; otherwise will use the usual thing.cljs$invoke$arity2 ? ... : thing.call(null, "foo", "bar")
          ;; with arg rebinding. could probably be a little smarter about this.
          ;; mostly want to optimize invokes for JS required code where most of the time it will be a function
          ;; and very very rarely (never in my code) something that implements IFn
          (and (:shadow.build/tweaks env)
               (ana/js-tag? ftag)))
      (make-invoke :fn env form fexpr @args-exprs)

      ;; no further optimizations for development code, always go through .call
      ;; FIXME: should still check arity
      ;; (def ^:dynamic *thing* ...) should go through .call always
      (or (not ana/*cljs-static-fns*)
          (:dynamic info))
      (make-invoke :dot-call env form fexpr @args-exprs)

      ;; (defn thing ...) might be variadic or have multiple arities
      (:fn-var info)
      (let [variadic? (:variadic? info)
            mps (:method-params info)
            mfa (:max-fixed-arity info)]
        (cond
          ;; if only one method, invoke directly, always a function
          (and (not variadic?) (= (count mps) 1))
          (make-invoke :fn env form fexpr @args-exprs)

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
           :args @args-exprs
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
            @args-exprs)

          ;; dispatch to variadic helper fn
          :else
          (make-invoke :fn env form fexpr @args-exprs)))

      ;; can always invoke multi-methods via ifn. don't need to check
      (= 'cljs.core/MultiFn ftag)
      (make-invoke :ifn env form fexpr @args-exprs)

      ;; fallback where something might be a function or IFn impl but we can't tell at compile time
      ;; need to wrap higher order calls to avoid repeating argument construction code
      ;;   (x {:foo "bar"})
      ;; would otherwise end up emitting code that repeats twice (map create)
      ;;   x.ifn1 ? x.ifn1(make_map(:foo, "bar")) : x.call(make_map(:foo, "bar"))
      ;; this can get rather large and impacts code size quite a bit

      ;; thus we wrap in
      ;;   (let [a1 {:foo "bar"}] (x a1))
      ;; ending with (auto-wrapped in IIFE when required)
      ;;   var a1 = make_map(:foo, "bar");
      ;;   x.ifn1 ? x.ifn(a1) : x.call(a1)

      ;; or function expressions
      ;;   ((deref m) x)
      ;;   (let [f (deref m)] (f x))

      :maybe-ifn
      (let [bind-f-expr? (not (symbol? f))
            bind-args? (not (shadow-all-values? args))]

        (if (or bind-f-expr? bind-args?)
          (ana/analyze env
            (-> (let [arg-syms (when bind-args? (take argc (repeatedly gensym)))
                      f-sym (when bind-f-expr? (gensym "fexpr__"))
                      bindings (cond-> []
                                 bind-args? (into (interleave arg-syms args))
                                 bind-f-expr? (conj f-sym f))]
                  `(let [~@bindings]
                     (~(if bind-f-expr? f-sym f)
                       ~@(if bind-args? arg-syms args))))
                (with-meta (meta form))))


          ;; direct thing.ifn1 ? thing.ifn(some, arg) : thing.call(some, arg)
          (make-invoke :maybe-ifn env form fexpr @args-exprs)
          )))))

;; gotta hate private vars sometimes
(defn comma-sep [xs]
  (interpose "," xs))

(defn protocol-prefix [psym]
  (str (-> (str psym)
           (.replace \. \$)
           (.replace \/ \$))
       "$"))

;; fixes an issue where the cljs.core variant assumes that `resolve-var` will only return
;; a js/foo symbol if x is also js/something which isn't true in many cases for shadow-cljs
;; where npm deps especially resolve some/foo to js/module$something.foo

;; https://github.com/clojure/clojurescript/blob/f884af0aef03147f3eef7a680579f704a7b6b81c/src/main/clojure/cljs/core.cljc#L991
;; this introduces the new "resolved" and uses that in the following cond-> as opposed to testing the js namespace on x only
(defn shadow-exists?
  "Return true if argument exists, analogous to usage of typeof operator
   in JavaScript."
  [&form &env x]
  (if (symbol? x)
    (let [resolved (:name (ana/resolve-var &env x))
          y (cond-> resolved
              (= "js" (namespace resolved)) name)
          segs (str/split (str (str/replace (str y) "/" ".")) #"\.")
          n (count segs)
          syms (map
                 #(vary-meta (symbol "js" (str/join "." %))
                    assoc :cljs.analyzer/no-resolve true)
                 (reverse (take n (iterate butlast segs))))
          js (str/join " && " (repeat n "(typeof ~{} !== 'undefined')"))]
      (-> (concat (list 'js* js) syms)
          ;; cljs.core/bool-expr is private ...
          (vary-meta assoc :tag 'boolean)))
    `(some? ~x)))


;; private is just annoying, get rid of that
;; we need access to these below

(alter-meta! #'cljs.core/to-property dissoc :private)
(alter-meta! #'cljs.core/ifn-invoke-methods dissoc :private)
(alter-meta! #'cljs.core/adapt-obj-params dissoc :private)


;; multimethod in core, although I don't see how this is ever going to go past 2 impls?
;; also added the metadata for easier externs inference
;; switched .. to nested . since .. looses form meta

(defn shadow-extend-prefix [tsym sym]
  (let [prop-sym (core/to-property sym)]
    (case (-> tsym meta :extend)
      :instance
      `(. ~tsym ~prop-sym)
      ;; :default
      `(. (. ~tsym ~'-prototype) ~prop-sym))))

(defn shadow-add-obj-methods [type type-sym sigs]
  (->> (if (ana/elide-to-string?)
         (remove (fn [[f]] (= 'toString f)) sigs)
         sigs)
       (map (fn [[f & meths :as form]]
              (let [[f meths] (if (vector? (first meths))
                                [f [(rest form)]]
                                [f meths])]
                `(set! ~(with-meta
                          (shadow-extend-prefix type-sym f)
                          {:shadow/object-fn f})
                   ~(with-meta `(fn ~@(map #(core/adapt-obj-params type %) meths)) (meta form))))))))

;; CLJS-3003
(defn shadow-add-ifn-methods [type type-sym [f & meths :as form]]
  (let [this-sym (with-meta 'self__ {:tag type})
        argsym (gensym "args")

        ;; we are emulating JS .call where the first argument will become this inside the fn
        ;; but this is not actually what we want when emulating IFn since we need "this"
        ;; so the first arg is always dropped instead and we dispatch to the actual protocol fns
        call-fn
        `(fn [unused#]
           (core/this-as ~this-sym
             (case (-> (core/js-arguments) (core/alength) (core/dec))
               ~@(reduce
                   (fn [form [args & body]]
                     (let [arity (-> args (count) (dec))]
                       (conj
                         form
                         arity
                         (concat
                           (list
                             (symbol (str ".cljs$core$IFn$_invoke$arity$" arity))
                             this-sym)
                           (for [ar (range arity)]
                             `(core/aget (core/js-arguments) ~(inc ar)))))))
                   []
                   meths)
               (throw (js/Error. (str "Invalid arity: " (-> (core/js-arguments) (core/alength) (core/dec)))))
               )))]

    (concat
      [`(set! ~(shadow-extend-prefix type-sym 'call) ~(with-meta call-fn (meta form)))
       `(set! ~(shadow-extend-prefix type-sym 'apply)
          ~(with-meta
             `(fn ~[this-sym argsym]
                (core/this-as ~this-sym
                  (.apply (.-call ~this-sym) ~this-sym
                    (.concat (core/array ~this-sym) (core/aclone ~argsym)))))
             (meta form)))]
      (core/ifn-invoke-methods type type-sym form))))

;; temp fix until there is a release with the Vector->VectorLite rename
;; (deftype Vector ...) clashes with cljs.core.Vector and ends up defining cljs.core.Vector although in a different ns
(defn shadow-parse-type
  [op env [_ tsym fields pmasks body :as form]]
  ;; not using resolve here, since a missing :excludes otherwise overwrites a type in a different ns?
  (let [t (symbol (str (:name (:ns env))) (str tsym))
        locals (reduce (fn [m fld]
                         (assoc m fld
                                  {:name fld
                                   :line (ana/get-line fld env)
                                   :column (ana/get-col fld env)
                                   :local :field
                                   :field true
                                   :mutable (-> fld meta :mutable)
                                   :unsynchronized-mutable (-> fld meta :unsynchronized-mutable)
                                   :volatile-mutable (-> fld meta :volatile-mutable)
                                   :tag (-> fld meta :tag)
                                   :shadow (m fld)}))
                 {} (if (= :defrecord op)
                      (concat fields '[__meta __extmap ^:mutable __hash])
                      fields))
        protocols (-> tsym meta :protocols)]
    (swap! env/*compiler* update-in [::ana/namespaces (-> env :ns :name) :defs tsym]
      (fn [m]
        (let [m (assoc (or m {})
                  :name t
                  :tag 'function
                  :type true
                  :num-fields (count fields)
                  :record (= :defrecord op))]
          (merge m
            (dissoc (meta tsym) :protocols)
            {:protocols protocols}
            (ana/source-info tsym env)))))
    {:op op :env env :form form :t t :fields fields :pmasks pmasks
     :tag 'function
     :protocols (disj protocols 'cljs.core/Object)
     :children [#_:fields :body]
     :body (ana/analyze (assoc env :locals locals) body)}))

(defn emit-ifn-args [args]
  ;; ifn past 20 args is supposed to supply rest arg, which to match varargs behavior
  ;; is emitted as a IndexedSeq over an array
  (let [c (count args)]
    (if (<= c 20)
      (comp/emits (comma-sep args))
      (let [head (take 20 args)
            tail (drop 20 args)]
        (comp/emits (comma-sep head) ", new cljs.core.IndexedSeq([" (comma-sep tail) "],0,null)")
        ))))

(defn as-ifn-prop [args]
  (str ".cljs$core$IFn$_invoke$arity$" (min 21 (count args))))

(defn install-hacks! []
  ;; cljs.analyzer tweaks
  (replace-fn! #'ana/load-core shadow-load-core)
  (replace-fn! #'ana/resolve-var shadow-resolve-var)
  ;; (replace-fn! #'ana/all-values? shadow-all-values?)
  (replace-fn! #'ana/parse-invoke* shadow-parse-invoke*)

  ;; cljs.compiler tweaks
  (replace-fn! #'comp/find-ns-starts-with shadow-find-ns-starts-with)

  (replace-fn! #'cljs.core/goog-define goog-define)
  (replace-fn! #'cljs.core/defonce shadow-defonce)
  (replace-fn! #'cljs.core/reify shadow-reify)

  (replace-fn! #'cljs.core/exists? shadow-exists?)

  (replace-fn! #'cljs.core/extend-prefix shadow-extend-prefix)
  (replace-fn! #'cljs.core/add-obj-methods shadow-add-obj-methods)
  (replace-fn! #'cljs.core/add-ifn-methods shadow-add-ifn-methods)

  (replace-fn! #'cljs.analyzer/parse-type shadow-parse-type)

  (.addMethod comp/emit* :invoke
    (fn [{f :fn :keys [invoke-type args env] :as expr}]
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
            (comp/emits f ".cljs$core$IFn$_invoke$arity$variadic(" (comma-sep (take mfa args))
              (when-not (zero? mfa) ",")
              "cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["
              (comma-sep (drop mfa args)) "], 0))"))

          :ifn
          (do (comp/emits f (as-ifn-prop args) "(")
              (emit-ifn-args args)
              (comp/emits ")"))

          :fn
          (comp/emits f "(" (comma-sep args) ")")

          :maybe-ifn
          (let [fprop (as-ifn-prop args)]
            ;; checks for ifn prop and invokes if found
            ;;   x.ifn ? x.ifn(...) : x(...)
            ;; or uses .call if fn-invoke-direct is false
            ;;   x.ifn ? x.ifn(...) : x.call(null, ...)
            (comp/emits "(" f fprop " ? " f fprop "(")
            (emit-ifn-args args)
            (comp/emits ") : " f)
            (if ana/*fn-invoke-direct*
              (comp/emits "(" (comma-sep args))
              ;; didn't have ifn property, assuming function and no max ifn args limit
              (comp/emits ".call(" (comma-sep (cons "null" args))))
            (comp/emits "))"))

          :dot-call
          (comp/emits f ".call(" (comma-sep (cons "null" args)) ")")))))

  (.addMethod comp/emit* :var (fn [expr] (shadow-emit-var expr)))
  (.addMethod comp/emit* :binding (fn [expr] (shadow-emit-var expr)))
  (.addMethod comp/emit* :js-var (fn [expr] (shadow-emit-var expr)))
  (.addMethod comp/emit* :local (fn [expr] (shadow-emit-var expr)))
  (.addMethod ana/parse '.
    (fn
      [_ env [_ target field & member+ :as form] _ opts]
      (ana/disallowing-recur (shadow-analyze-dot env target field member+ form opts))))

  ;; remove these for now, not worth the trouble
  ;; (replace-fn! #'test/deftest @#'shadow-deftest)
  ;; (replace-fn! #'test/use-fixtures @#'shadow-use-fixtures)
  )
