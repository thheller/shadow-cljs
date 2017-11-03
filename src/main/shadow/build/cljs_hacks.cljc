(ns shadow.build.cljs-hacks
  (:require [cljs.analyzer]
            [cljs.core]))

;; these things to some slight modifications to cljs.analyzer
;; there are some odd checks related to JS integration
;; which sort of conflict with the way shadow-cljs handles this

;; it operates without the modifications but has some weird edge cases


;; it also fully replaces the :infer-externs implementation
;; the default implementation was far more ambitious by trying to keep everything typed
;; which only works reliably if everything is annotated properly
;; new users are unlikely to do that
;; basically all of cljs.core is untyped as well which means the typing is not as useful anyways

;; this impl just records all js globals that were accessed in a given namespace
;; as well as all properties identified on js objects
;; (:require ["something" :as x :refer (y)])
;; both x and y are tagged as 'js and will be recorded


;; to be fair I could have built the simplified version on top of the typed version
;; but there were I a few aspects I didn't quite understand
;; so this was easier for ME, not better.

(in-ns 'cljs.analyzer)

(def conj-to-set (fnil conj #{}))

(defn shadow-js-access-global [current-ns global]
  {:pre [(symbol? current-ns)
         (string? global)]}
  (swap! env/*compiler* update-in
    [::namespaces current-ns :shadow/js-access-global] conj-to-set global))

(defn shadow-js-access-property [current-ns prop]
  {:pre [(symbol? current-ns)
         (string? prop)]}
  (when-not (string/starts-with? prop "cljs$")
    (swap! env/*compiler* update-in
      [::namespaces current-ns :shadow/js-access-properties] conj-to-set prop)))

(defn resolve-js-var [ns sym current-ns]
  ;; quick hack to record all accesses to any JS mod
  ;; (:require ["react" :as r :refer (foo]) (r/bar ...)
  ;; will record foo+bar
  (let [prop (name sym)
        qname (symbol "js" (str ns "." prop))]
    (shadow-js-access-property current-ns prop)

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
  (some? (get-in @env/*compiler* [:js-module-index (name module)])))

(defn resolve-cljs-var [ns sym current-ns]
  (merge (gets @env/*compiler* ::namespaces ns :defs sym)
         {:name (symbol (str ns) (str sym))
          :ns ns}))

(defn resolve-ns-var [ns sym current-ns]
  (cond
    (js-module-exists? ns)
    (resolve-js-var ns sym current-ns)

    (contains? (:goog-names @env/*compiler*) ns)
    {:name (symbol (str ns) (str sym))
     :ns ns}

    :else
    (resolve-cljs-var ns sym current-ns)
    ))

(defn invokeable-ns?
  "Returns true if ns is a required namespace and a JavaScript module that
   might be invokeable as a function."
  [alias env]
  (when-let [ns (resolve-ns-alias env alias nil)]
    (js-module-exists? ns)))

(defn resolve-invokeable-ns [alias current-ns env]
  (let [ns (resolve-ns-alias env alias)]
    {:name ns
     :tag 'js
     :ret-tag 'js
     :ns 'js}))

(def known-safe-js-globals
  "symbols known to be closureJS compliant namespaces"
  #{"cljs"
    "goog"})

(defn resolve-var
  "Resolve a var. Accepts a side-effecting confirm fn for producing
   warnings about unresolved vars."
  ([env sym] (resolve-var env sym nil))
  ([env sym confirm]
   (let [locals (:locals env)
         current-ns (-> env :ns :name)
         sym-ns-str (namespace sym)]
     (if (= "js" sym-ns-str)
       (do (when (contains? locals (-> sym name symbol))
             (warning :js-shadowed-by-local env {:name sym}))
           ;; always record all fully qualified js/foo.bar calls
           (let [[global & props]
                 (clojure.string/split (name sym) #"\.")]

             ;; do not record access to
             ;; js/goog.string.format
             ;; js/cljs.core.assoc
             ;; just in case someone does that, we won't need externs for those
             (when-not (contains? known-safe-js-globals global)
               (shadow-js-access-global current-ns global)
               (when (seq props)
                 (doseq [prop props]
                   (shadow-js-access-property current-ns prop)))))

           {:name sym
            :ns 'js
            :tag 'js
            :ret-tag 'js})

       (let [s (str sym)
             lb (get locals sym)
             current-ns-info (gets @env/*compiler* ::namespaces current-ns)]

         (cond
           (some? lb) lb

           (some? sym-ns-str)
           (let [ns sym-ns-str
                 ns (symbol (if (= "clojure.core" ns) "cljs.core" ns))
                 ;; thheller: remove the or
                 full-ns (resolve-ns-alias env ns (symbol ns))
                 ;; strip ns
                 sym (symbol (name sym))]
             (when (some? confirm)
               (when (not= current-ns full-ns)
                 (confirm-ns env full-ns))
               (confirm env full-ns sym))
             (resolve-ns-var full-ns sym current-ns))

           ;; FIXME: would this not be better handled if checked before calling resolve-var
           ;; and analyzing this properly?
           (dotted-symbol? sym)
           (let [idx (.indexOf s ".")
                 prefix (symbol (subs s 0 idx))
                 suffix (subs s (inc idx))]
             (if-some [lb (get locals prefix)]
               {:name (symbol (str (:name lb)) suffix)}
               (if-some [full-ns (gets current-ns-info :imports prefix)]
                 {:name (symbol (str full-ns) suffix)}
                 (if-some [info (gets current-ns-info :defs prefix)]
                   (merge info
                          {:name (symbol (str current-ns) (str sym))
                           :ns current-ns})
                   (merge (gets @env/*compiler* ::namespaces prefix :defs (symbol suffix))
                          {:name (if (= "" prefix) (symbol suffix) (symbol (str prefix) suffix))
                           :ns prefix})))))

           (some? (gets current-ns-info :uses sym))
           (let [full-ns (gets current-ns-info :uses sym)]
             (resolve-ns-var full-ns sym current-ns))

           (some? (gets current-ns-info :renames sym))
           (let [qualified-symbol (gets current-ns-info :renames sym)
                 full-ns (symbol (namespace qualified-symbol))
                 sym (symbol (name qualified-symbol))]
             (resolve-ns-var full-ns sym current-ns))

           (some? (gets current-ns-info :imports sym))
           (recur env (gets current-ns-info :imports sym) confirm)

           (some? (gets current-ns-info :defs sym))
           (do
             (when (some? confirm)
               (confirm env current-ns sym))
             (resolve-cljs-var current-ns sym current-ns))

           (core-name? env sym)
           (do
             (when (some? confirm)
               (confirm env 'cljs.core sym))
             (resolve-cljs-var 'cljs.core sym current-ns))

           (invokeable-ns? s env)
           (resolve-invokeable-ns s current-ns env)

           :else
           (do (when (some? confirm)
                 (confirm env current-ns sym))
               (resolve-cljs-var current-ns sym current-ns)
               )))))))

(defn analyze-dot [env target field member+ form]
  (let [v [target field member+]
        {:keys [dot-action target method field args]} (build-dot-form v)
        enve (assoc env :context :expr)
        targetexpr (analyze enve target)
        form-meta (meta form)
        target-tag (:tag targetexpr)
        prop (or field method)
        tag (or (:tag form-meta)
                (and (js-tag? target-tag) 'js)
                nil)]


    ;; simplified *warn-on-infer* warnings since we do not care about them being typed
    ;; we just need ^js not ^js/Foo.Bar
    (when (and (or (nil? target-tag)
                   (= 'any target-tag))
               (not (string/starts-with? (str prop) "cljs$")))

      (warning :infer-warning env {:warn-type :target :form form}))

    (when (js-tag? tag)
      (shadow-js-access-property (-> env :ns :name) (str prop)))

    (case dot-action
      ::access (let [children [targetexpr]]
                 {:op :dot
                  :env env
                  :form form
                  :target targetexpr
                  :field field
                  :children children
                  :tag tag})
      ::call (let [argexprs (map #(analyze enve %) args)
                   children (into [targetexpr] argexprs)]
               {:op :dot
                :env env
                :form form
                :target targetexpr
                :method method
                :args argexprs
                :children children
                :tag tag}))))

;; thheller: changed tag inference to always use tag on form first
;; destructured bindings had meta in their :init
;; https://dev.clojure.org/jira/browse/CLJS-2385
(defn get-tag [e]
  (if-some [tag (-> e :form meta :tag)]
    tag
    (if-some [tag  (-> e :tag)]
      tag
      (-> e :info :tag)
      )))

(in-ns 'cljs.core)

;; https://dev.clojure.org/jira/browse/CLJS-1439

(core/defmacro goog-define
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
  [sym default]
  (assert-args goog-define
    (core/or (core/string? default)
             (core/number? default)
             (core/true? default)
             (core/false? default)) "a string, number or boolean as default value")
  (core/let [defname (comp/munge (core/str *ns* "/" sym))
             type    (core/cond
                       (core/string? default) "string"
                       (core/number? default) "number"
                       (core/or (core/true? default) (core/false? default)) "boolean")]
    `(do
       (declare ~(core/vary-meta sym
                   (fn [m]
                     (core/cond-> m
                       (core/not (core/contains? m :tag))
                       (core/assoc :tag (core/symbol type))
                       ))))
       (~'js* ~(core/str "/** @define {" type "} */"))
       (goog/define ~defname ~default))))