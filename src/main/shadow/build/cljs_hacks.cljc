(ns shadow.build.cljs-hacks
  (:require [cljs.analyzer]))


;; these things to some slight modifications to cljs.analyzer
;; there are some odd checks related to JS integration
;; which sort of conflict with the way shadow-cljs handles this

;; it operates without the modifications but has some weird edge cases

(in-ns 'cljs.analyzer)

(def shadow-js-tag
  (with-meta 'js {:prefix ['ShadowJS]}))

(defn resolve-js-var [ns sym]
  ;; quick hack to record all accesses to any JS mod
  ;; (:require ["react" :as r :refer (foo]) (r/bar ...)
  ;; would record foo+bar
  (let [prop (name sym)
        qname (symbol (str ns "." prop))]
    (swap! env/*compiler* update :shadow/js-properties conj prop)

    {:name qname
     :tag shadow-js-tag
     :ret-tag shadow-js-tag
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

(defn resolve-cljs-var [ns sym]
  (merge (gets @env/*compiler* ::namespaces ns :defs sym)
         {:name (symbol (str ns) (str sym))
          :ns ns}))

(defn resolve-ns-var [ns sym]
  (if (js-module-exists? ns)
    (resolve-js-var ns sym)
    (resolve-cljs-var ns sym)
    ))

(defn resolve-var
  "Resolve a var. Accepts a side-effecting confirm fn for producing
   warnings about unresolved vars."
  ([env sym] (resolve-var env sym nil))
  ([env sym confirm]
   (let [locals (:locals env)
         sym-ns-str (namespace sym)]
     (if #?(:clj  (= "js" sym-ns-str)
            :cljs (identical? "js" sym-ns-str))
       (do
         (when (contains? locals (-> sym name symbol))
           (warning :js-shadowed-by-local env {:name sym}))
         (let [pre (->> (string/split (name sym) #"\.") (map symbol) vec)]
           (when-not (has-extern? pre)
             (swap! env/*compiler* update-in
               (into [::namespaces (-> env :ns :name) :externs] pre) merge {}))
           (merge
             {:name sym
              :ns   'js
              :tag  (with-meta (or (js-tag pre) (:tag (meta sym)) 'js) {:prefix pre})}
             (when-let [ret-tag (js-tag pre :ret-tag)]
               {:js-fn-var true
                :ret-tag ret-tag}))))
       (let [s  (str sym)
             lb (get locals sym)
             current-ns (-> env :ns :name)
             current-ns-info (gets @env/*compiler* ::namespaces current-ns)]
         (cond
           (some? lb) lb

           (some? sym-ns-str)
           (let [ns      sym-ns-str
                 ns      (if #?(:clj  (= "clojure.core" ns)
                                :cljs (identical? "clojure.core" ns))
                           "cljs.core"
                           ns)
                 ;; thheller: removed bad check here
                 full-ns (resolve-ns-alias env ns (symbol ns))]
             (when (some? confirm)
               (when (not= current-ns full-ns)
                 (confirm-ns env full-ns))
               (confirm env full-ns (symbol (name sym))))
             (resolve-ns-var full-ns sym))

           (dotted-symbol? sym)
           (let [idx    (.indexOf s ".")
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
             (resolve-ns-var full-ns sym))

           (some? (gets current-ns-info :renames sym))
           (let [qualified-symbol (gets current-ns-info :renames sym)
                 full-ns (symbol (namespace qualified-symbol))
                 sym     (symbol (name qualified-symbol))]
             (resolve-ns-var full-ns sym))

           (some? (gets current-ns-info :imports sym))
           (recur env (gets current-ns-info :imports sym) confirm)

           (some? (gets current-ns-info :defs sym))
           (do
             (when (some? confirm)
               (confirm env current-ns sym))
             (resolve-cljs-var current-ns sym))

           (core-name? env sym)
           (do
             (when (some? confirm)
               (confirm env 'cljs.core sym))
             (resolve-cljs-var 'cljs.core sym))

           (invokeable-ns? s env)
           (resolve-invokeable-ns s current-ns env)

           :else
           (do
             (when (some? confirm)
               (confirm env current-ns sym))
             (resolve-cljs-var current-ns sym)
             )))))))

(defn invokeable-ns?
  "Returns true if ns is a required namespace and a JavaScript module that
   might be invokeable as a function."
  [alias env]
  (let [ns (resolve-ns-alias env alias nil)]
    ;; whats the point of this (required? ...) check?
    ;; we first call resolve-ns-alias, which looks at the :require
    ;; then it checks again if it was required? seems redundant?
    (and ns
         #_(required? ns env)
         (js-module-exists? ns))))

(defn resolve-invokeable-ns [alias current-ns env]
  (let [ns (resolve-ns-alias env alias)]
    {:name ns
     :tag shadow-js-tag
     :ret-tag shadow-js-tag
     :ns 'js}))

