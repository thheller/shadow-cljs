(ns shadow.cljs.ns-form
  "ns parser based on spec"
  (:require [clojure.spec.alpha :as s]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cljs.compiler :as cljs-comp]
            [shadow.cljs.util :as util]))

;; [clojure.core.specs.alpha :as cs]
;; too many differences in CLJS ns to make use of those
(defn reduce-> [init reduce-fn coll]
  (reduce reduce-fn init coll))

(defn reduce-kv-> [init reduce-fn coll]
  (reduce-kv reduce-fn init coll))

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
  (reduce
    (fn [m [key opt :as x]]
      (let [{:keys [key value]} opt]
        (when (contains? m key)
          (throw (ex-info "duplicate opt key" {:opt opt :m m})))
        (assoc m key value)))
    {}
    opts))

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

(defn reduce-require [ns-info [key require]]
  (case key
    :sym
    (-> ns-info
        (merge-require :requires require require)
        (add-dep require))
    :seq
    (let [{:keys [lib opts]}
          require

          {:keys [as refer refer-macros include-macros rename] :as opts-m}
          (opts->map opts)

          refer
          (if (seq rename)
            (remove rename refer)
            refer)]

      (if (string? lib)
        ;; string require ;; FIXME: should warn on refer-macros or include-macros
        (-> ns-info
            (add-dep lib)
            (update :js-requires conj lib)
            (cond->
              as
              (merge-require :js-aliases as lib))
            (reduce->
              (merge-require-fn :js-refers lib)
              refer)
            (reduce-kv->
              (fn [ns-info rename-to rename-from]
                (update ns-info :js-renames assoc rename-from (symbol (str lib) (str rename-to))))
              rename))

        ;; symbol require
        (-> ns-info
            (add-dep lib)
            (merge-require :requires lib lib)
            (cond->
              as
              (merge-require :requires as lib)

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
              rename))))))

(defn reduce-require-macros [ns-info [key require]]
  (case key
    :sym
    (-> ns-info
        (merge-require :require-macros require require))

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

      (cond
        ;; (:import [goog.foo.Class]) is a no-op since no names are mentioned
        ;; FIXME: worthy of a warning?
        (not (seq names))
        ns-info

        (string? lib)
        (-> ns-info
            (update :js-requires conj lib)
            (add-dep lib)
            (reduce->
              (merge-require-fn :js-imports lib)
              names))

        (symbol? lib)
        (-> ns-info
            (reduce->
              (fn [ns-info sym]
                (let [fqn (symbol (str lib "." sym))]
                  (-> ns-info
                      (merge-require :imports sym fqn)
                      (merge-require :requires sym fqn)
                      (add-dep fqn))))
              names))))))

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
        (let [{:keys [only rename] :as opts}
              (opts->map opts)

              only
              (if (seq rename)
                (remove rename only)
                only)]

          (if (string? lib)
            ;; string (:use ["some" :only (foo)])
            (-> ns-info
                (update :js-requires conj lib)
                (reduce->
                  (merge-require-fn :js-refers lib)
                  only)
                (reduce-kv->
                  (merge-rename-fn :js-renames lib)
                  rename
                  ))

            ;; symbol (:use [some :only (foo)])
            (-> ns-info
                (merge-require :requires lib lib)
                (add-dep lib)
                (reduce->
                  (merge-require-fn :uses lib)
                  only)
                (reduce-kv->
                  (merge-rename-fn :renames lib)
                  rename
                  )))))
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

(defn parse [form]
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
          {:excludes #{}
           :seen #{}
           :name (vary-meta name merge meta)
           :meta meta
           :imports nil ;; {Class ns}
           :requires nil
           :require-macros nil
           :deps []
           :uses nil
           :use-macros nil
           :renames {} ;; seems to be only one that is never nil in cljs.core
           :rename-macros nil
           ;; these must be rewritten later
           ;; not doing it here because of relative requires
           ;; "../bar" needs to know which file we are compiling
           ;; felt shitty using a binding to shim in a resolve-fn
           :js-requires #{}
           :js-refers {}
           :js-aliases {}
           :js-renames {}}

          ns-info
          (reduce reduce-ns-clause ns-info clauses)

          {:keys [deps] :as ns-info}
          (if (= 'cljs.core name)
            ns-info
            (-> ns-info
                (update :requires assoc 'cljs.core 'cljs.core)
                (update :deps
                  (fn [deps]
                    (->> (concat '[cljs.core] deps)
                         ;; just in case someone manually required cljs.core
                         (distinct)
                         (into [])
                         )))))]

      ;; FIXME: shadow.cljs uses :require-order since that was there before :deps
      ;; should probably rename all references of :require-order to :deps to match cljs
      ;; for now just copy
      (assoc ns-info :require-order deps)
      )))

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

      (assoc ns-info :require-order deps :flags (into #{} flags))
      )))

(defn make-npm-alias [lib]
  ;; must escape . and /
  ;; react-dom/server
  ;; would end up as shadow.npm.react_dom.server
  ;; which may mess up if shadow.npm.react_dom has a .server
  ;; generates kinda absurd names for relative things
  ;; ../../src/main/foo.js
  ;; shadow.npm._DOT__DOT__SLASH__DOT__DOT__SLASH_src_SLASH_main_SLASH_foo_DOT_js
  ;; rewriting to make it shorter
  ;; ../../../../src/main/shadow/cljs/ui/Foo
  ;; shadow.npm._UPPPP_src_SLASH_main_SLASH_shadow_SLASH_cljs_SLASH_ui_SLASH_Foo
  ;; doesn't really matter since :advanced will collapse it anyways
  ;; but during dev the names get absurd
  (-> lib
      (str/replace "../" "_UP_")
      (str/replace "/" "_SLASH_")
      (cljs-comp/munge)
      ;; munge doesn't munge extensions like .js
      (str/replace "." "_DOT_")
      (str/replace "__UP" "P")
      (->> (str "shadow.npm."))
      (symbol)))

(defn relative-path-from-output-dir [output-dir src-file rel-path]
  (let [output-dir
        (-> output-dir
            (.getCanonicalFile)
            (.toPath))

        src-file
        (-> src-file
            (.getParentFile))

        rel-file
        (-> (io/file src-file rel-path)
            (.getCanonicalFile)
            (.toPath))]

    (-> (.relativize output-dir rel-file)
        (.toString))))

(defn resolve-js-require
  [{:keys [output-dir] :as state}
   js-require
   src-file]

  (cond
    (not (str/starts-with? js-require "."))
    js-require

    (nil? src-file)
    ;; this is because webpack and others can't see files in jars
    ;; FIXME: think about copying the files out of the jar?
    ;; would need to copy everything since we can't tell what the other file might need
    (throw (ex-info "relative requires are not supported without file" {:js-require js-require :output-dir output-dir}))

    :else
    (let [rel (relative-path-from-output-dir output-dir src-file js-require)]
      ;; resolving is relative to output-dir
      ;; that is not very obvious in some cases
      ;; not sure if worth logging though
      #_(util/log state {:type :js-resolve
                         :output-dir output-dir
                         :file src-file
                         :js-require js-require
                         :rel rel})
      rel)
    ))

(defn rewrite-js-requires
  [{:keys [js-requires js-refers js-aliases js-imports js-renames deps] :as ns-info} state src-file]
  {:pre [(map? state)]}
  (if (empty? js-requires)
    ns-info
    (let [resolved
          (reduce
            (fn [resolved lib]
              (let [rel (resolve-js-require state lib src-file)]
                (assoc resolved lib rel)))
            {}
            js-requires)

          aliases
          (reduce-kv
            (fn [aliases _ resolved]
              (assoc aliases resolved (make-npm-alias resolved)))
            {}
            resolved)

          ns-for-lib
          (fn [lib]
            (cond
              (symbol? lib)
              lib
              (string? lib)
              (->> (get resolved lib)
                   (get aliases))))]

      (-> ns-info
          (assoc :js-resolved resolved
                 :js-ns-aliases aliases
                 :js-requires (into #{} (map resolved) js-requires))

          (reduce->
            (fn [ns-info lib]
              (let [ns (ns-for-lib lib)]
                (merge-require ns-info :requires ns ns)))
            js-requires)

          ;; :js-imports {Component "react"}
          (reduce-kv->
            (fn [ns-info class lib]
              (let [ns (ns-for-lib lib)
                    fqn (symbol (str ns "." class))]
                (-> ns-info
                    (merge-require :imports class ns)
                    (merge-require :requires class fqn)
                    )))
            js-imports)

          ;; :js-aliases {r "react", rdom "react-dom/server", foo "./foo.js"}
          (reduce-kv->
            (fn [ns-info alias lib]
              (let [ns (ns-for-lib lib)]
                (-> ns-info
                    (merge-require :requires alias ns))))
            js-aliases)

          ;; :js-refers {createElement "react"}
          (reduce-kv->
            (fn [ns-info refer lib]
              (let [ns (ns-for-lib lib)]
                (merge-require ns-info :uses refer ns)))
            js-refers)

          (update :deps #(into [] (map ns-for-lib) %))
          (update :require-order #(into [] (map ns-for-lib) %))
          ))))

(defn rewrite-js-requires-for-name
  [ns-info state src-name]
  (rewrite-js-requires ns-info state (get-in state [:sources src-name :file])))
