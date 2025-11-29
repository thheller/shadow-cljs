(ns shadow.build.ns-form
  "ns parser based on spec"
  (:require [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [clojure.string :as str]
            [cljs.compiler :as cljs-comp]
            [shadow.cljs.util :as util :refer (reduce-> reduce-kv->)]
            [shadow.build.data :as data]))

;; [clojure.core.specs.alpha :as cs]
;; too many differences in CLJS ns to make use of those

(s/def ::local-name (s/and simple-symbol? #(not= '& %)))

;; some.ns or "npm" package
(s/def ::lib
  #(or (simple-symbol? %)
       (string? %)))

(s/def ::ns-name simple-symbol?)

(s/def ::syms
  (s/coll-of simple-symbol?))

;; kw-args
(s/def ::exclude
  (s/cat
    :key #{:exclude}
    :value ::syms))

(s/def ::as
  (s/cat
    :key #{:as}
    :value ::local-name))

(s/def ::as-alias
  (s/cat
    :key #{:as-alias}
    :value ::local-name))

(s/def ::default
  (s/cat
    :key #{:default}
    :value ::local-name))

(s/def ::refer
  (s/cat
    :key #{:refer}
    :value ::syms))

(s/def ::refer-macros
  (s/cat
    :key #{:refer-macros}
    :value ::syms))

(s/def ::only
  (s/cat
    :key #{:only}
    :value ::syms))

(s/def ::include-macros
  (s/cat
    :key #{:include-macros}
    :value boolean?))

(s/def ::rename
  (s/cat
    :key #{:rename}
    :value (s/map-of simple-symbol? simple-symbol?)))

;; :require

(s/def ::require-opt
  (s/alt
    :as ::as
    :as-alias ::as-alias
    :refer ::refer
    :refer-macros ::refer-macros
    :rename ::rename
    :default ::default
    :include-macros ::include-macros
    ))

(s/def ::require
  (s/or
    :sym
    simple-symbol?
    :seq
    (s/cat
      :lib ::lib
      :opts (s/* ::require-opt))))

(s/def ::require-global
  (s/or
    :sym
    simple-symbol?
    :seq
    (s/cat
      :lib simple-symbol?
      :opts (s/* ::require-opt))))

(s/def ::require-flag #{:reload :reload-all})

(s/def ::ns-require
  (s/spec
    (s/cat
      :clause
      #{:require}
      :requires
      (s/* ::require)
      :flags
      (s/* ::require-flag)
      )))

(s/def ::ns-require-global
  (s/spec
    (s/cat
      :clause
      #{:require-global}
      :requires
      (s/* ::require-global)
      )))

(s/def ::require-macros-opt
  (s/alt
    :as ::as
    :refer ::refer
    :rename ::rename))

(s/def ::require-macros
  (s/or
    :sym
    simple-symbol?
    :seq
    (s/cat
      :lib ::ns-name
      :opts (s/* ::require-macros-opt))))

(s/def ::ns-require-macros
  (s/spec
    (s/cat
      :clause
      #{:require-macros}
      :requires
      (s/* ::require-macros)
      :flags
      (s/* ::require-flag)
      )))

(s/def ::quoted-require
  (s/spec
    (s/cat
      :quote
      '#{quote}
      :require
      ::require)))

(s/def ::repl-require
  (s/cat
    :clause
    '#{require}

    :quoted-requires
    (s/+ ::quoted-require)

    :flags
    (s/* ::require-flag)
    ))

;; :import

(s/def ::import
  (s/or
    :sym
    simple-symbol?
    :seq
    (s/cat
      :lib ::lib
      :names (s/* simple-symbol?))))

(s/def ::ns-import
  (s/spec
    (s/cat
      :clause
      #{:import :import-macros}
      :imports
      (s/* ::import))))

;; :refer-clojure
(s/def ::refer-clojure-opt
  (s/alt
    :exclude ::exclude
    :rename ::rename
    ))

(s/def ::ns-refer-clojure
  (s/spec
    (s/cat
      :clause
      #{:refer-clojure}
      :opts
      (s/+ ::refer-clojure-opt))))

(s/def ::refer-global-opt
  (s/alt
    :only ::only
    :rename ::rename
    ))

(s/def ::ns-refer-global
  (s/spec
    (s/cat
      :clause
      #{:refer-global}
      :opts
      (s/+ ::refer-global-opt))))

(s/def ::use-macro
  (s/spec
    (s/cat
      :ns
      simple-symbol?
      :only
      #{:only}
      :syms
      ::syms)))

(s/def ::ns-use-macros
  (s/spec
    (s/cat
      :clause
      #{:use-macros}
      :uses
      (s/+ ::use-macro))))

(s/def ::use-opt
  (s/alt
    :only ::only
    :rename ::rename
    ))

(s/def ::use
  (s/spec
    (s/cat
      :lib
      ::lib
      :opts
      (s/+ ::use-opt))))

(s/def ::ns-use
  (s/spec
    (s/cat
      :clause
      #{:use}
      :uses
      (s/* ::use))))

;; :ns

(s/def ::ns-clauses
  (s/*
    (s/alt
      :refer-clojure ::ns-refer-clojure
      :require ::ns-require
      :require-macros ::ns-require-macros
      :require-global ::ns-require-global
      :refer-global ::ns-refer-global
      :import ::ns-import
      :use-macros ::ns-use-macros
      :use ::ns-use)))

(s/def ::ns-form
  (s/cat
    :ns '#{ns}
    :name simple-symbol?
    :docstring (s/? string?)
    :meta (s/? map?)
    :clauses ::ns-clauses))

(defmulti reduce-ns-clause (fn [ns-info [key clause]] key))

(defn opts->map [opts]
  (let [{:keys [refer rename only] :as opts-m}
        (reduce
          (fn [m [key opt :as x]]
            (let [{:keys [key value]} opt]
              (when (contains? m key)
                (throw (ex-info "duplicate opt key" {:opt opt :m m})))
              (assoc m key value)))
          {}
          opts)

        refer
        (if (seq rename)
          (remove rename refer)
          refer)

        only
        (if (seq rename)
          (remove rename only)
          only)]

    (-> opts-m
        (cond->
          (seq refer)
          (assoc :refer refer)
          (seq only)
          (assoc :only only)))))

(defn check-alias-conflict! [ns-info ns alias]
  (let [conflict
        (or (get-in ns-info [:requires alias])
            ;; apparently it is ok for :require-macros to clash with a regular alias? no need to check it then
            ;; not sure why this is allowed
            ;; https://github.com/clojure/clojurescript/blob/afe2cf748fb77c5194bac0608e32358e15da067d/src/main/cljs/cljs/js.cljs#L12
            ;; https://github.com/clojure/clojurescript/blob/afe2cf748fb77c5194bac0608e32358e15da067d/src/main/cljs/cljs/js.cljs#L15
            #_(get-in ns-info [:require-macros alias])
            (get-in ns-info [:reader-aliases alias]))]
    (when (and conflict
               (not= conflict ns)
               (not (contains? (:ns-aliases ns-info) ns)))

      (throw
        (ex-info (format "The alias %s is already used for namespace %s" alias conflict)
          {:tag ::require-conflict
           :ns-info ns-info
           :alias alias
           :conflict conflict}))))

  ns-info)

(defn merge-require [ns-info merge-key alias ns]
  (update ns-info merge-key assoc alias ns))

(defn merge-require-fn [merge-key ns]
  #(merge-require %1 merge-key %2 ns))

(defn merge-rename-fn [merge-key ns]
  (fn [ns-info rename-to rename-from]
    (update ns-info merge-key assoc rename-from (symbol (str ns) (str rename-to)))))

(defn conj-distinct [deps sym]
  (->> (conj deps sym)
       (distinct)
       (into [])))

(defn add-dep [{:keys [flags] :as ns-info} sym]
  (-> ns-info
      (update :deps conj-distinct sym)
      (cond->
        (seq (:require flags))
        (update :reload-deps conj-distinct sym)
        )))

(defn process-string-require [ns-info lib opts]
  (-> ns-info
      (add-dep lib)
      ;; string require, delayed resolve until compile time, so only add for now
      ;; using a vector and not a map in case requires of the same lib are repeated
      ;; ["foo" :refer (A)]
      ;; ["foo" :refer (B)]
      (update :js-deps conj (assoc opts ::lib lib))))

(defn remove-entry [syms sym-to-remove]
  (->> syms
       (remove sym-to-remove)
       (vec)))

(defn assert-as-alias-used-alone [lib opts-m]
  (when-not (= 1 (count opts-m))
    (throw (ex-info ":as-alias cannot be used with other options"
             {:tag ::as-alias-not-alone
              :opts opts-m
              :lib lib}))))

(defn maybe-add-dep [ns-info lib]
  ;; self-require, from REPL
  ;; must not be merged into regular :deps since that creates a circular dependency
  ;; which may lead to compilation issues later
  (if (= lib (:name ns-info))
    (assoc ns-info :self-require true)
    (add-dep ns-info lib)))

(defn process-symbol-require
  [ns-info lib {:keys [js as as-alias default refer refer-macros include-macros import rename only] :as opts-m}]

  ;; FIXME: remove this, just a sanity check since the first impl was incorrect
  (assert (not (contains? opts-m :imports)))

  ;; :refer (f) :rename {f other-f} should clear out the :refer (f) since its no longer accessible
  (let [rename-syms (set (keys rename))
        refer (remove-entry refer rename-syms)
        refer-macros (remove-entry refer-macros rename-syms)

        ;; if only :as-alias is used we don't need to load the namespace
        ;; any other variant must load and can optionally also contain as-alias
        ;; [something :as-alias :refer (foo)]
        ;; [something :as foo :as-alias bar]
        load? (not (and (= 1 (count opts-m)) as-alias))]

    (if (not load?)
      ;; only reader-alias, no loading or dependency
      (-> ns-info
          (check-alias-conflict! lib as-alias)
          (update :as-aliases assoc as-alias lib)
          (update :reader-aliases assoc as-alias lib))

      ;; regular none :as-alias require
      (-> ns-info
          (maybe-add-dep lib)
          (merge-require :requires lib lib)
          (cond->
            as
            (-> (check-alias-conflict! lib as)
                (merge-require :requires as lib))

            as-alias
            (update :reader-aliases assoc as-alias lib)

            default
            (update :renames assoc default (symbol (str lib) "default"))

            import
            (-> (merge-require :imports import lib)
                (merge-require :requires import lib))

            (or include-macros (seq refer-macros))
            (-> (merge-require :require-macros lib lib)
                (cond->
                  as
                  (merge-require :require-macros as lib))))
          (reduce->
            (merge-require-fn :uses lib)
            refer)
          (reduce->
            (merge-require-fn :use-macros lib)
            refer-macros)
          (reduce-kv->
            (fn [ns-info var-to-rename rename-sym]
              (update ns-info :renames assoc rename-sym (symbol (str lib) (str var-to-rename))))
            rename)
          (reduce->
            (merge-require-fn :uses lib)
            only)))))

(defn process-require-global
  [ns-info lib {:keys [js as refer rename] :as opts-m}]

  ;; :refer (f) :rename {f other-f} should clear out the :refer (f) since its no longer accessible
  (let [rename-syms (set (keys rename))
        refer (remove-entry refer rename-syms)]

    ;; regular none :as-alias require
    (-> ns-info
        (merge-require :require-global lib lib)
        (cond->
          as
          (-> (check-alias-conflict! lib as)
              (merge-require :require-global as lib)))
        (reduce->
          (merge-require-fn :use-global lib)
          refer)
        (reduce-kv->
          (fn [ns-info var-to-rename rename-sym]
            (update ns-info :rename-global assoc rename-sym [lib var-to-rename]))
          rename))))

(defn process-require [ns-info lib opts]
  (if (string? lib)
    (process-string-require ns-info lib opts)
    (process-symbol-require ns-info lib opts)))

(defn reduce-require [ns-info [key require]]
  (case key
    :sym
    (process-symbol-require ns-info require {})

    :seq
    (let [{:keys [lib opts]}
          require

          opts-m
          (opts->map opts)]

      (process-require ns-info lib opts-m)
      )))

(defn reduce-require-global [ns-info [key require]]
  (case key
    :sym
    (process-symbol-require-global ns-info require {})

    :seq
    (let [{:keys [lib opts]}
          require

          opts-m
          (opts->map opts)]

      (process-require-global ns-info lib opts-m)
      )))

(defn reduce-require-macros [ns-info [key require]]
  (case key
    :sym
    (-> ns-info
        (merge-require :require-macros require require))

    :seq
    (let [{ns :lib opts :opts}
          require

          {:keys [as refer rename] :as opts-m}
          (opts->map opts)]

      (when (string? ns)
        (throw (ex-info "require-macros only works with symbols not strings" {:require require :ns-info ns-info})))

      (-> ns-info
          (merge-require :require-macros ns ns)
          (cond->
            as
            (merge-require :require-macros as ns))
          (reduce->
            (merge-require-fn :use-macros ns)
            refer)
          (reduce-kv->
            (merge-rename-fn :rename-macros ns)
            rename)))))

(defmethod reduce-ns-clause :require [ns-info [_ clause]]
  (let [{:keys [requires flags]} clause]
    (-> ns-info
        (update :seen conj :require)
        ;; need to remember if :require or :require-macros had :reload/:reload-all
        (update :flags assoc :require (into #{} flags))
        (reduce-> reduce-require requires))))

(defmethod reduce-ns-clause :require-global [ns-info [_ clause]]
  (let [{:keys [requires]} clause]
    (-> ns-info
        (update :seen conj :require-global)
        (reduce-> reduce-require-global requires))))

(defmethod reduce-ns-clause :require-macros [ns-info [_ clause]]
  (let [{:keys [requires flags]} clause]
    (-> ns-info
        (update :seen conj :require-macros)
        ;; need to remember if :require or :require-macros had :reload/:reload-all
        (update :flags assoc :require-macros (into #{} flags))
        (reduce-> reduce-require-macros requires)
        )))

(defn reduce-import [ns-info [key import]]
  (case key
    :sym ;; a.fully-qualified.Name, never a string
    (let [class (-> import str (str/split #"\.") last symbol)]
      (-> ns-info
          (merge-require :requires class import)
          (merge-require :imports class import)
          (add-dep import)))

    :seq
    (let [{:keys [lib names]} import]
      ;; (:import [goog.foo.Class]) is a no-op since no names are mentioned
      ;; FIXME: worthy of a warning?
      (if-not (seq names)
        ns-info
        (reduce
          (fn [ns-info class]
            (let [fqn (symbol (str lib "." class))]
              (process-require ns-info fqn {:import class})))
          ns-info
          names)))))

(defmethod reduce-ns-clause :import [ns-info [_ clause]]
  (let [{:keys [imports]} clause]
    (reduce reduce-import ns-info imports)))

(defmethod reduce-ns-clause :refer-clojure [ns-info [_ clause]]
  (let [{:keys [exclude rename] :as opts}
        (opts->map (:opts clause))]

    (-> ns-info
        (update :excludes set/union (set exclude))
        (reduce-kv->
          (merge-rename-fn :renames 'cljs.core)
          rename))))

(defmethod reduce-ns-clause :refer-global [ns-info [_ clause]]
  (let [{:keys [only rename] :as opts}
        (opts->map (:opts clause))]

    (-> ns-info
        (reduce->
          (fn [info sym]
            (update info :require-global assoc sym sym))
          only)
        (reduce-kv->
          (fn [info from to]
            (update info :require-global assoc to from))
          rename
          ))))

(defmethod reduce-ns-clause :use [ns-info [_ clause]]
  (let [{:keys [uses]} clause]
    (reduce
      (fn [ns-info {:keys [lib opts] :as use}]
        (let [opts (opts->map opts)]
          (process-require ns-info lib opts)))
      ns-info
      uses)))

(defmethod reduce-ns-clause :use-macros [ns-info [_ clause]]
  (let [{:keys [uses]} clause]
    (reduce
      (fn [ns-info {:keys [ns only syms] :as use}]
        (-> ns-info
            (update :require-macros assoc ns ns)
            (reduce->
              (merge-require-fn :use-macros ns)
              syms)))
      ns-info
      uses)))

(def empty-ns-info
  {:excludes #{}
   :seen #{}
   :imports nil ;; {Class ns}
   :requires nil
   :require-macros nil
   :reader-aliases {}
   :deps []
   :uses nil
   :use-macros nil
   :renames {} ;; seems to be only one that is never nil in cljs.core
   :rename-macros nil
   :js-deps []
   ;; map of which clause had which flags
   ;; (:require [foo.bar] :reload)
   ;; (:require-macros [foo.bar] :reload-all)
   ;; {:require #{:reload}
   ;;  :require-macros #{:reload-all}}
   :flags {}})

(defn parse
  ([form]
   (parse empty-ns-info form))
  ([ns-info form]
   (let [conformed
         (s/conform ::ns-form form)]

     (when (= conformed ::s/invalid)
       (throw (ex-info "failed to parse ns form"
                (assoc (s/explain-data ::ns-form form)
                  :tag ::invalid-ns
                  :input form))))

     (let [{:keys [name docstring meta clauses] :or {meta {}}}
           conformed

           name-meta
           (clojure.core/meta name)

           meta
           (cond-> meta
             docstring
             (update :doc str docstring)

             (seq name-meta)
             (merge name-meta))

           ns-info
           (assoc ns-info
             :meta meta
             :name (vary-meta name merge meta))

           ns-info
           (reduce reduce-ns-clause ns-info clauses)]

       (if (= 'cljs.core name)
         (update ns-info :deps
           (fn [deps]
             (->> (concat '[goog #_shadow.cljs_helpers] deps)
                  (distinct)
                  (into []))))
         (-> ns-info
             (update :requires assoc 'cljs.core 'cljs.core 'goog 'goog)
             ;; FIXME: this might blow up CLJS since it has all kinds of special cases for cljs.core
             (update :require-macros assoc 'cljs.core 'cljs.core)
             (update :deps
               (fn [deps]
                 (->> (concat '[goog #_shadow.cljs_helpers cljs.core] deps)
                      ;; just in case someone manually required cljs.core
                      (distinct)
                      (into [])
                      )))
             ))))))

(defn rewrite-ns-aliases
  [{:keys [requires require-macros uses use-macros deps renames] :as ast}
   {:keys [ns-aliases] :as state}]

  (if-not (seq ns-aliases)
    ast
    (let [rewrite-ns
          (fn [ns]
            (get ns-aliases ns ns))

          rewrite-ns-map
          (fn [ns-map alias-self?]
            (reduce-kv
              (fn [ns-map alias ns]
                (if-not (contains? ns-aliases ns)
                  ns-map
                  (let [target (rewrite-ns ns)]
                    (-> ns-map
                        (assoc alias target)
                        (cond->
                          alias-self?
                          (assoc target target))))))
              ns-map
              ns-map))]

      (assoc ast
        :deps
        (into [] (map rewrite-ns) deps)
        :requires
        (rewrite-ns-map requires true)
        :require-macros
        (rewrite-ns-map require-macros true)
        :uses
        (rewrite-ns-map uses false)
        :use-macros
        (rewrite-ns-map use-macros false)
        :renames
        (reduce-kv
          (fn [renames var fqn-name]
            (let [ns (symbol (namespace fqn-name))
                  alias-ns (get ns-aliases ns)]
              (if-not alias-ns
                renames
                (assoc renames var (symbol (str alias-ns) (name fqn-name))))))
          renames
          renames)
        :ns-aliases
        ns-aliases
        ))))

(defn rewrite-js-deps
  "rewrites string requires based on the aliases they resolved to
   this can only be done after resolve since that makes the aliases"
  [{:keys [name js-deps deps] :as ns-info} build-state]
  (if-not (seq js-deps)
    ns-info

    (let [js-aliases
          (reduce
            (fn [js-aliases {js-require ::lib}]
              ;; get throws if not found
              (let [alias (data/get-string-alias build-state name js-require)]
                (assoc js-aliases js-require alias)))
            {}
            js-deps)

          ;; update :deps to make CLJS happy
          ;; we are only using :deps from the resource which remains unchanged
          deps
          (->> deps
               (map (fn [dep]
                      (get js-aliases dep dep)))
               (into []))]

      (-> ns-info
          (assoc :js-aliases js-aliases :deps deps)
          (util/reduce->
            (fn [ns-info {js-require ::lib :as opts}]
              (process-symbol-require ns-info (get js-aliases js-require) (assoc opts :js true)))
            js-deps)))))

