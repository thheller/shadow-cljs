(ns shadow.build.ns-form
  "ns parser based on spec"
  (:require [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cljs.compiler :as cljs-comp]
            [shadow.cljs.util :as util :refer (reduce-> reduce-kv->)]
            [shadow.build.data :as data])
  (:import (java.nio.file Paths FileSystems)))

;; [clojure.core.specs.alpha :as cs]
;; too many differences in CLJS ns to make use of those

(s/def ::local-name (s/and simple-symbol? #(not= '& %)))

;; some.ns or "npm" package
(s/def ::lib
  #(or (simple-symbol? %)
       (string? %)))

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

(s/def ::default
  (s/cat
    :key #{:default}
    :value ::local-name))

(s/def ::refer
  (s/cat
    :key #{:refer :refer-macros}
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
    :refer ::refer
    :refer-macros ::refer
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

(s/def ::require-flag #{:reload :reload-all})

(s/def ::ns-require
  (s/spec
    (s/cat
      :clause
      #{:require :require-macros}
      :requires
      (s/+ ::require)
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

    :quoted-require
    ::quoted-require

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
      :names (s/+ simple-symbol?))))

(s/def ::ns-import
  (s/spec
    (s/cat
      :clause
      #{:import :import-macros}
      :imports
      (s/+ ::import))))

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
      (s/+ ::use))))

;; :ns

(s/def ::ns-clauses
  (s/*
    (s/alt
      :refer-clojure ::ns-refer-clojure
      :require ::ns-require
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

(defn merge-require [ns-info merge-key sym ns]
  (let [conflict (get-in ns-info [merge-key sym])]
    (when (and conflict
               (not= conflict ns))

      (throw
        (ex-info (format "conflict on \"%s\" by \"%s\" used by \"%s\"" sym ns conflict)
          {:ns-info ns-info
           :merge-key merge-key
           :sym sym
           :ns ns}))))
  (update ns-info merge-key assoc sym ns))

(defn merge-require-fn [merge-key ns]
  #(merge-require %1 merge-key %2 ns))

(defn merge-rename-fn [merge-key ns]
  (fn [ns-info rename-to rename-from]
    (update ns-info merge-key assoc rename-from (symbol (str ns) (str rename-to)))))

(defn add-dep [ns-info sym]
  (update ns-info :deps
    (fn [deps]
      (->> (conj deps sym)
           (distinct)
           (into [])))))

(defn process-string-require [ns-info lib {:keys [as refer only rename] :as opts}]
  ;; FIXME: should warn on refer-macros or include-macros
  ;; string require, delayed resolve until compile time
  (-> ns-info
      (add-dep lib)
      ;; merge cause there can be (:require ["react"]) and (:import ["react" Component])
      (update-in [:js-deps lib] merge opts)))

(defn process-symbol-require
  [ns-info lib {:keys [js as default refer refer-macros include-macros import rename only] :as opts-m}]

  ;; FIXME: remove this, just a sanity check since the first impl was incorrect
  (assert (not (contains? opts-m :imports)))

  (-> ns-info
      (add-dep lib)
      (merge-require :requires lib lib)
      (cond->
        as
        (merge-require :requires as lib)

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
        (fn [ns-info rename-to rename-from]
          (update ns-info :renames assoc rename-from (symbol (str lib) (str rename-to))))
        rename)
      (reduce->
        (merge-require-fn :uses lib)
        only)))

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
  (let [{:keys [clause requires flags]} clause]
    (-> ns-info
        (update :seen conj clause)
        (update :flags assoc clause (into #{} flags))
        (cond->
          (= :require clause)
          (reduce-> reduce-require requires)
          (= :require-macros clause)
          (reduce-> reduce-require-macros requires)
          ))))

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
   :deps []
   :uses nil
   :use-macros nil
   :renames {} ;; seems to be only one that is never nil in cljs.core
   :rename-macros nil
   :js-deps {}})

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

           meta
           (cond-> meta
             docstring
             (update :doc str docstring))

           ns-info
           (assoc ns-info
             :meta meta
             :name (vary-meta name merge meta))

           ns-info
           (reduce reduce-ns-clause ns-info clauses)]

       (if (= 'cljs.core name)
         ns-info
         (-> ns-info
             (update :requires assoc 'cljs.core 'cljs.core 'goog 'goog)
             ;; FIXME: this might blow up CLJS since it has all kinds of special cases for cljs.core
             (update :require-macros assoc 'cljs.core 'cljs.core)
             (update :deps
               (fn [deps]
                 (->> (concat '[goog cljs.core] deps)
                      ;; just in case someone manually required cljs.core
                      (distinct)
                      (into [])
                      )))
             ))))))

(defn merge-repl-require [ns-info require-args]
  (let [conformed (s/conform ::repl-require require-args)]

    (when (= conformed ::s/invalid)
      (throw (ex-info "failed to parse ns require"
               (assoc (s/explain-data ::repl-require require-args)
                 :tag ::invalid-require))))

    (let [require
          (get-in conformed [:quoted-require :require])

          {:keys [flags]}
          conformed

          {:keys [deps] :as ns-info}
          (reduce-require ns-info require)]

      (assoc ns-info :flags (into #{} flags))
      )))

(defn rewrite-ns-aliases
  [{:keys [requires uses deps] :as ast}
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
                          (assoc ns target))))))
              ns-map
              ns-map))]

      (assoc ast
        :deps
        (into [] (map rewrite-ns) deps)
        :requires
        (rewrite-ns-map requires true)
        :uses
        (rewrite-ns-map uses false))
      )))

(defn rewrite-js-deps
  "rewrites string requires based on the aliases they resolved to
   this can only be done after resolve since that makes the aliases"
  [{:keys [name js-deps deps] :as ns-info} build-state]
  (if-not (seq js-deps)
    ns-info

    (let [js-aliases
          (reduce-kv
            (fn [js-aliases js-require _]
              ;; get throws if not found
              (let [alias (data/get-string-alias build-state name js-require)]
                (assoc js-aliases js-require alias)))
            {}
            js-deps)

          ;; update :deps to make CLJS happy
          ;; we are only :deps from the resource which remains unchanged
          deps
          (->> deps
               (map (fn [dep]
                      (get js-aliases dep dep)))
               (into []))]

      (-> ns-info
          (assoc :js-aliases js-aliases :deps deps)
          (util/reduce-kv->
            (fn [ns-info js-require opts]
              (process-symbol-require ns-info (get js-aliases js-require) (assoc opts :js true)))
            js-deps)
          ))))

