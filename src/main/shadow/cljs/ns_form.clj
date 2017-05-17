(ns shadow.cljs.ns-form
  "ns parser based on spec"
  (:require [clojure.spec.alpha :as s]
            [clojure.core.specs.alpha :as cs]
            [clojure.pprint :refer (pprint)]
            [cljs.compiler :as cljs-comp]))

(defn reduce-> [init reduce-fn coll]
  (reduce reduce-fn init coll))

(defn reduce-kv-> [init reduce-fn coll]
  (reduce-kv reduce-fn init coll))

;; didnt use most of ::cs since the CLJS ns form is quite different

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
    :value ::cs/local-name))

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

(defn check [spec form]
  (s/explain spec form)
  (pprint (s/conform spec form)))

;; (check ::as '[:as foo])

;; :require

(s/def ::require-opt
  (s/alt
    :as ::as
    :refer ::refer
    :refer-macros ::refer
    :rename ::rename
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

(s/def ::ns-require
  (s/spec
    (s/cat
      :clause
      #{:require :require-macros}
      :requires
      (s/+ ::require)
      :flags
      (s/* #{:reload :reload-all})
      )))

(comment
  (check ::ns-require
    '(:require
       just.a.sym
       [goog.string :as gstr]
       [some.foo :as foo :refer (x y z) :refer-macros (foo bar) :rename {x c}]
       ["react" :as react]
       :reload)))

;; :import

(s/def ::import
  (s/or
    :sym
    simple-symbol?
    :single
    (s/cat :name simple-symbol?)
    :seq
    (s/cat
      :ns ::lib
      :names (s/+ simple-symbol?))))

(s/def ::ns-import
  (s/spec
    (s/cat
      :clause
      #{:import :import-macros}
      :imports
      (s/+ ::import))))

(comment
  (check ::ns-import
    '(:import
       that.Class
       [another Foo Bar]
       [just.Single]
       )))

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

(comment
  (check ::ns-refer-clojure
    '(:refer-clojure
       :exclude (assoc)
       :rename {conj jnoc})))

(s/def ::use-macro
  (s/spec
    (s/cat
      :sym
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

(comment
  (check ::ns-use-macros
    '(:use-macros [macro-use :only (that-one)])))

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

(comment
  (check ::ns-use
    '(:use [something.fancy :only [everything] :rename {everything nothing}])))

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

(defn make-npm-alias [str]
  (symbol "npm$alias." (cljs-comp/munge str)))

(defn opts->map [opts]
  (reduce
    (fn [m [key opt :as x]]
      (let [{:keys [key value]} opt]
        (when (contains? m key)
          (throw (ex-info "duplicate opt key" {:opt opt :m m})))
        (assoc m key value)))
    {}
    opts))

(defn reduce-require [ns-info [key require]]
  (case key
    :sym
    (-> ns-info
        (update :requires assoc require require)
        (update :require-order conj require))
    :seq
    (let [{:keys [lib opts]}
          require

          {:keys [as refer refer-macros include-macros rename] :as opts-m}
          (opts->map opts)

          refer
          (if (seq rename)
            (remove rename refer)
            refer)

          ns
          (if (string? lib)
            (make-npm-alias lib)
            lib)]

      (-> ns-info
          (update :require-order conj ns)
          (update :requires assoc ns ns)
          (cond->
            as
            (update :requires assoc as ns)

            (string? lib)
            (update :js-requires assoc lib ns)

            (or include-macros (seq refer-macros))
            (-> (update :require-macros assoc ns ns)
                (cond->
                  as
                  (update :require-macros assoc as ns))))
          ;; FIXME: ensure unqiue
          (reduce->
            #(update %1 :uses assoc %2 ns)
            refer)
          ;; FIXME: ensure unqiue
          (reduce->
            #(update %1 :use-macros assoc %2 ns)
            refer-macros)
          (reduce-kv->
            (fn [ns-info rename-to rename-from]
              (update ns-info :renames assoc rename-from (symbol (str ns) (str rename-to))))
            rename)))))

(defn reduce-require-macros [ns-info [key require]]
  (case key
    :sym
    (-> ns-info
        (update :require-macros assoc require require))
    :seq
    (let [{ns :lib opts :opts}
          require

          {:keys [as refer rename] :as opts-m}
          (opts->map opts)

          refer
          (if (seq rename)
            (remove rename refer)
            refer)]

      (when (string? ns)
        (throw (ex-info "require-macros only works with symbols not strings" {:require require :ns-info ns-info})))

      (-> ns-info
          (update :require-macros assoc ns ns)
          (cond->
            as
            (update :require-macros assoc as ns))
          ;; FIXME: ensure unqiue
          (reduce->
            #(update %1 :use-macros assoc %2 ns)
            refer)
          ;; FIXME: ensure unqiue
          (reduce-kv->
            (fn [ns-info rename-to rename-from]
              (update ns-info :rename-macros assoc rename-from (symbol (str ns) (str rename-to))))
            rename)))))

(defmethod reduce-ns-clause :require [ns-info [_ clause]]
  (let [{:keys [clause requires flags]} clause]
    (prn [:require-clause clause])

    (-> ns-info
        (update :seen conj clause)
        (assoc :require-flags (into #{} flags))
        (cond->
          (= :require clause)
          (reduce-> reduce-require requires)
          (= :require-macros clause)
          (reduce-> reduce-require-macros requires)
          ))))

(defmethod reduce-ns-clause :use [ns-info [_ clause]]
  ns-info)

(defmethod reduce-ns-clause :import [ns-info [_ clause]]
  ns-info)

(defmethod reduce-ns-clause :refer-clojure [ns-info [_ clause]]
  ns-info)

(defmethod reduce-ns-clause :use-macros [ns-info [_ clause]]
  ns-info)

(defn parse-ns [form]
  (let [conformed
        (s/conform ::ns-form form)]

    (when (= conformed ::s/invalid)
      (throw (ex-info "failed to parse ns form"
               (assoc (s/explain-data ::ns-form form)
                      :tag ::error
                      :input form))))

    (let [{:keys [name docstring meta clauses] :or {meta {}}}
          conformed

          meta
          (cond-> meta
            docstring
            (update :doc str docstring))

          ns-info
          {:excludes #{}
           :seen #{}
           :name (with-meta name meta)
           :meta meta
           :js-requires {}
           :requires {}
           :require-order []
           :require-macros {}
           :uses {}
           :use-macros {}
           :renames {}
           :rename-macros nil ;; starts as nil in cljs.core
           }]

      (reduce reduce-ns-clause ns-info clauses))))