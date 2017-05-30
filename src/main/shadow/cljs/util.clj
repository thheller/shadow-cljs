(ns shadow.cljs.util
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [cljs.env :as env]
            [cljs.compiler :as comp]
            [cljs.core]
            [shadow.cljs.log :as log]) ;; not really, just to ensure it is loaded so we can query it form macros?
  (:import (clojure.lang Namespace)))

(defn compiler-state? [state]
  (true? (::is-compiler-state state)))

(defn foreign? [{:keys [type] :as src}]
  (= :foreign type))

(defn file-basename [^String path]
  (let [idx (.lastIndexOf path "/")]
    (.substring path (inc idx))
    ))

(defn flat-filename [filename]
  (str/replace filename #"/" "."))

(defn log [state log-event]
  {:pre [(compiler-state? state)]}
  (log/log* (:logger state) state log-event)
  state)

(def ^{:dynamic true} *time-depth* 0)

(defmacro with-logged-time
  [[state msg] & body]
  `(let [msg# ~msg
         start# (System/currentTimeMillis)

         evt#
         (assoc msg#
                :timing :enter
                :start start#
                :depth *time-depth*)]
     (log ~state evt#)
     (let [result#
           (binding [*time-depth* (inc *time-depth*)]
             ~@body)

           stop#
           (System/currentTimeMillis)

           evt#
           (assoc msg#
                  :timing :exit
                  :depth *time-depth*
                  :stop stop#
                  :duration (- stop# start#))]
       (log (if (compiler-state? result#) result# ~state) evt#)
       result#)
     ))

(def require-option-keys
  #{:as
    :refer
    :refer-macros
    :include-macros
    :rename})

(def use-option-keys
  #{:only
    :rename})

(def uses-key
  {:requires :uses
   :require-macros :use-macros})

;; not actually an error to require things twice, clojure does allow this as well
#_(defn- check-require-once! [{:keys [name requires] :as ns-info} require-ns]
    (when (some #(= % require-ns) (vals requires))
      (throw (ex-info (format "NS:%s has duplicate require/use for %s" name require-ns) {:ns-info ns-info}))
      ))

(defn is-macro?
  ([ns sym]
   (is-macro? (symbol (str ns) (str sym))))
  ([fqn]
   (when-let [the-var (find-var fqn)]
     (.isMacro the-var))))

(defn- merge-renames [ns-info ns {:keys [rename] :as options}]
  {:pre [(map? options)
         (symbol? ns)]}
  (cond
    (not (contains? options :rename))
    ns-info

    (map? rename)
    (reduce-kv
      (fn [ns-info rename-from rename-to]
        (let [fqn (symbol (str ns) (str rename-from))]
          (when-let [conflict
                     (or (get-in ns-info [:uses rename-to])
                         (get-in ns-info [:use-macros rename-to])
                         (get-in ns-info [:renames rename-to])
                         (get-in ns-info [:rename-macros rename-to]))]
            (throw (ex-info
                     (format "conflicting renames in ns form. tried to rename \"%s\" to \"%s\" in \"%s\", but \"%s\" already uses \"%s\""
                       rename-from
                       rename-to
                       ns
                       conflict
                       rename-to)
                     {:ns ns
                      :rename-from rename-from
                      :rename-to rename-to
                      :conflict conflict})))

          (cond
            ;; rename for normal refered vars
            (or (= ns 'cljs.core)
                (= ns (get-in ns-info [:uses rename-from])))
            (-> ns-info
                (assoc-in [:renames rename-to] fqn)
                (update :uses dissoc rename-from))

            ;; rename for refered macros
            ;; FIXME: the later infer-renames-for-macros can figure this out better
            ;; doing this here without loading the macros so the result
            ;; is closer to what parse-ns from cljs does
            (= ns (get-in ns-info [:use-macros rename-from]))
            (-> ns-info
                (assoc-in [:rename-macros rename-to] fqn)
                (update :use-macros dissoc rename-from))

            :else
            (throw (ex-info
                     (format "Renamed symbol \"%s\" from ns \"%s\" not referred"
                       rename-from
                       ns)
                     {:missing-refer rename-from
                      :ns ns})))))
      ns-info
      rename)

    :else
    (throw (ex-info "rename is expected to be a map" {:ns ns :options options}))))

(defn parse-ns-require-parts
  [key {:keys [form] :as ns-info} parts]
  (reduce
    (fn [ns-info part]
      (cond
        (or (= :requires key)
            (= :require-macros key))
        (cond
          ;; (:require foo) => {:require {foo foo}}
          (symbol? part)
          (-> ns-info
              (assoc-in [key part] part)
              (cond->
                (= key :requires)
                (update :require-order conj part)))

          (or (= :reload-all part)
              (= :reload part))
          (assoc ns-info part (set (vals (get ns-info key))))

          ;; (:require [foo :as bar :refer (baz)]) => {:require {foo bar} :use {baz foo}}
          (sequential? part)
          (let [[require-ns & more] part]
            (when-not (even? (count more))
              (throw (ex-info "Only [lib.ns & options] and lib.ns specs supported in :require / :require-macros" {:form form :part part})))

            (let [options (apply hash-map more)]
              ;; FIXME: check that each key only appears once, (:require [some :as x :as y]) should not be valid
              (when-not (set/subset? (keys options) require-option-keys)
                (throw (ex-info (str "Only :as alias and :refer (names) options supported in " key) {:form form :part part})))

              (let [alias
                    (:as options)

                    ns-info
                    (assoc-in ns-info [key require-ns] require-ns)

                    ;; :require-macros should not be in require-order since it won't have a js file to load
                    ns-info
                    (if (= :requires key)
                      (update ns-info :require-order conj require-ns)
                      ns-info)

                    ns-info
                    (if alias
                      (assoc-in ns-info [key alias] require-ns)
                      ns-info)

                    ns-info
                    (if-let [refer (get options :refer)]
                      (do (when-not (sequential? refer)
                            (throw (ex-info ":refer (names) must be sequential" {:form form :part part})))
                          (reduce
                            (fn [ns-info refer]
                              (assoc-in ns-info [(get uses-key key) refer] require-ns))
                            ns-info
                            refer))
                      ns-info)

                    ns-info
                    (let [refer-macros
                          (:refer-macros options)

                          merge-refer-macros
                          (fn [ns-info]
                            (reduce
                              (fn [ns-info use]
                                (assoc-in ns-info [:use-macros use] require-ns))
                              ns-info
                              refer-macros))]
                      (if (or (:include-macros options) (sequential? refer-macros))
                        (-> ns-info
                            (assoc-in [:require-macros require-ns] require-ns)
                            (merge-refer-macros)
                            (cond->
                              alias
                              (assoc-in [:require-macros alias] require-ns)))
                        ns-info))

                    ns-info
                    (merge-renames ns-info require-ns options)]

                ns-info)))

          :else
          (throw (ex-info "Unsupported part in form" {:form form :part part :key key})))

        ;; (:use [foo :only (baz)]) => {:uses {baz foo}}
        (or (= :uses key)
            (= :use-macros key))
        (do (when-not (sequential? part)
              (throw (ex-info "Only [lib.ns :only (names)] specs supported in :use / :use-macros" {:form form :part part})))


            (let [[use-ns & more] part]
              (when-not (even? (count more))
                (throw (ex-info "Only [lib.ns & options] and lib.ns specs supported in :use / :use-macros" {:form form :part part})))


              (let [{:keys [only] :as options} (apply hash-map more)]
                (when-not (set/subset? (keys options) use-option-keys)
                  (throw (ex-info (str "Only :only (names)/:rename {from to} options supported in " key) {:form form :part part})))

                (let [ns-info
                      (if (= :uses key)
                        (-> ns-info
                            (assoc-in [:requires use-ns] use-ns)
                            (update :require-order conj use-ns))
                        (assoc-in ns-info [:require-macros use-ns] use-ns))

                      ns-info
                      (reduce
                        (fn [ns-info use]
                          (assoc-in ns-info [key use] use-ns))
                        ns-info
                        only)

                      ns-info
                      (merge-renames ns-info use-ns options)]

                  ns-info
                  ))))

        :else
        (throw (ex-info "how did you get here?" {:ns-info ns-info :key key :part part}))
        ))

    ns-info
    parts))

(defn parse-ns-refer-clojure
  [ns-info args]
  (when-not (even? (count args))
    (throw (ex-info "Only (:refer-clojure :exclude (foo bar)) allowed" {})))
  (let [{:keys [exclude] :as options} (apply hash-map args)]
    (-> ns-info
        (update-in [:excludes] into exclude)
        (merge-renames 'cljs.core options))))

(defn import-fully-qualified-symbol [ns-info the-symbol]
  (let [class
        (-> the-symbol str (str/split #"\.") last symbol)

        conflict
        (get-in ns-info [:imports class])

        ns
        (:name ns-info)]
    (when conflict
      (throw (ex-info
               (format "ns: %s has a dumplicate import for class: %s%nA: %s%nB: %s" ns class conflict the-symbol)
               {:class class
                :ns (:name ns-info)
                :import the-symbol
                :conflict conflict})))
    (-> ns-info
        (assoc-in [:imports class] the-symbol)
        (assoc-in [:requires class] the-symbol)
        (update :require-order conj the-symbol))))

(defn parse-ns-import
  [ns-info parts]
  (reduce
    (fn [ns-info part]
      (cond
        (symbol? part)
        (import-fully-qualified-symbol ns-info part)

        (sequential? part)
        (let [[ns & classes] part]
          (when-not (and (symbol? ns)
                         (every? symbol? classes))
            (throw (ex-info "[lib.ns Ctor*] violation" {:part part})))

          (reduce
            (fn [ns-info class]
              (let [fqn (symbol (str ns "." class))]
                (import-fully-qualified-symbol ns-info fqn)))
            ns-info
            classes))))
    ns-info
    parts))

(defn parse-ns
  "expected a parse ns form from the reader, returns a map with the extracted information"
  [[head ns-name & more :as form]]
  (when-not (= 'ns head)
    (throw (ex-info "Not an (ns ...) form" {:form form})))
  (when-not (symbol? ns-name)
    (throw (ex-info "Namespaces must be named by a symbol." {:form form})))

  (let [first-arg
        (first more)

        [meta more]
        (cond
          (and (string? first-arg) (map? (second more)))
          [(assoc (second more) :doc first-arg) (drop 2 more)]
          (string? first-arg)
          [{:doc first-arg} (rest more)]
          (map? first-arg)
          [first-arg (rest more)]
          :else
          [nil more])

        name
        (vary-meta ns-name merge meta)

        ns-info
        (reduce
          (fn [{:keys [seen] :as ns-info} part]
            (when-not (sequential? part)
              (throw (ex-info "unrecognized ns part" {:form form :part part})))

            (let [[head & tail] part]
              (when (contains? seen head)
                (throw (ex-info (str "Only one " head " allowed") {:form form :part part})))
              (-> (cond
                    (= :require head)
                    (parse-ns-require-parts :requires ns-info tail)

                    (= :require-macros head)
                    (parse-ns-require-parts :require-macros ns-info tail)

                    (= :use head)
                    (parse-ns-require-parts :uses ns-info tail)

                    (= :use-macros head)
                    (parse-ns-require-parts :use-macros ns-info tail)

                    (= :import head)
                    (parse-ns-import ns-info tail)

                    (= :refer-clojure head)
                    (parse-ns-refer-clojure ns-info tail)

                    :else
                    (throw (ex-info "Unsupport part in ns form" {:form form :part part})))

                  (update-in [:seen] conj head))))

          {:excludes #{}
           :seen #{}
           :name name
           :meta meta
           :requires {}
           :require-order []
           :require-macros {}
           :uses {}
           :use-macros {}
           :renames {}
           :rename-macros {}}
          more)

        ns-info
        (if (= 'cljs.core name)
          ns-info
          (-> ns-info
              (update :requires merge
                '{cljs.core cljs.core})
              (update :require-order
                (fn [ro]
                  (->> ro
                       (concat '[cljs.core])
                       (distinct)
                       (into []))))))]

    (let [required-ns
          (->> (:requires ns-info)
               (vals)
               (into #{}))
          required-ns
          (->> (:imports ns-info)
               (vals)
               (into required-ns))]
      (when (not= (count (:require-order ns-info))
                  (count required-ns))
        ;; require-order should always match all required cljs namespaces
        ;; but since :requires is a map that contains {alias full-name, full-name full-name}
        ;; convert it to a set first, this also checks if require-order contains duplicates
        ;; since the counts wont match
        ;; FIXME: sanity check this properly, add better error since any error is a bug in this code
        (throw (ex-info "messed up requires" {:ns-info ns-info
                                              :required-ns required-ns}))))

    ns-info))


(defn find-macros-in-ns
  [name]
  (->> (ns-publics name)
       (reduce-kv
         (fn [m var-name the-var]
           (if (.isMacro ^clojure.lang.Var the-var)
             (let [macro-meta
                   (meta the-var)

                   macro-info
                   (let [ns (.getName ^Namespace (:ns macro-meta))]
                     (assoc macro-meta
                            :ns ns
                            :name (symbol (str ns) (str var-name))))]
               (assoc m var-name macro-info))
             m))
         {})))


(def ^{:private true} require-lock (Object.))

(defn load-macros
  [{:keys [name require-macros use-macros] :as ns-info}]
  (if (= 'cljs.core name)
    ns-info
    (let [macro-namespaces
          (-> #{}
              (into (vals require-macros))
              (into (vals use-macros)))]

      (binding [ana/*cljs-ns* name]
        (locking require-lock
          (doseq [macro-ns macro-namespaces]
            (try
              (require macro-ns)
              (catch Exception e
                (throw (ex-info (format "failed to require macro-ns:%s, it was required by:%s" macro-ns name) {:ns-info ns-info} e)))))))

      (if (contains? macro-namespaces name)
        (let [macros (find-macros-in-ns name)]
          (assoc ns-info :macros macros))
        ns-info))))

(defn infer-macro-require
  "infer (:require [some-ns]) that some-ns may come with macros
   must be used after load-macros"
  [{:keys [requires] :as ns-info}]
  (reduce
    (fn [ast [used-name used-ns]]
      (let [macros (get-in @env/*compiler* [::ana/namespaces used-ns :macros])]
        (if (nil? macros)
          ast
          (update-in ast [:require-macros] assoc used-name used-ns)
          )))
    ns-info
    requires))

(defn infer-macro-use
  "infer (:require [some-ns :refer (something)]) that something might be a macro
   must be used after load-macros"
  [{:keys [uses] :as ns-info}]
  (reduce
    (fn [ast [used-name used-ns]]
      (let [macros (get-in @env/*compiler* [::ana/namespaces used-ns :macros])]
        (if (or (nil? macros)
                (not (contains? macros used-name)))
          ast
          (update-in ast [:use-macros] assoc used-name used-ns)
          )))
    ns-info
    uses))

(defn ana-is-cljs-def?
  "checked whether a symbol in a given namespace is defined in CLJS (not a macro)"
  ([fqn]
   {:pre [(symbol? fqn)
          (namespace fqn)
          (name fqn)]}
   (ana-is-cljs-def?
     (symbol (namespace fqn))
     (symbol (name fqn))))
  ([ns sym]
   {:pre [(symbol? ns)
          (symbol? sym)]}
   (not= (get-in @env/*compiler* [::ana/namespaces ns :defs sym] ::not-found) ::not-found)))

(defn infer-renames-for-macros
  [{:keys [renames] :as ns-info}]
  (reduce-kv
    (fn [ns-info rename-to source-sym]
      (if-not (is-macro? source-sym)
        ns-info
        (-> ns-info
            ;; remove the :rename if it is only a macro and not a cljs var
            (cond->
              (not (ana-is-cljs-def? source-sym))
              (update :renames dissoc rename-to))

            (update :rename-macros assoc rename-to source-sym))))
    ns-info
    renames))

(defn error [env error-type error-data]
  (let [msg (ana/error-message error-type error-data)]
    (throw
      (ex-info msg
        (-> (ana/source-info env)
            (assoc :tag :cljs/analysis-error
                   :error-type error-type
                   :msg msg
                   :extra error-data))))))

(defn check-uses! [{:keys [env uses use-macros] :as ns-info}]
  (doseq [[sym lib] use-macros]
    (when (ana/missing-use-macro? lib sym)
      (error env :undeclared-ns-form {:type "macro" :lib lib :sym sym})))

  (doseq [[sym lib] uses]
    (when (and (not (str/starts-with? (str lib) "shadow.npm."))
               (not (ana-is-cljs-def? lib sym))
               (not (contains? (get-in @env/*compiler* [::ana/namespaces lib :macros]) sym)))
      (error env :undeclared-ns-form {:type "var" :lib lib :sym sym}))))

(defn check-renames! [{:keys [renames rename-macros] :as ns-info}]
  (doseq [[rename-to rename-from] renames
          :let [rename-ns (symbol (namespace rename-from))
                rename-name (symbol (name rename-from))]]
    ))


(defn- namespace-name->js-obj [^String ns]
  (let [parts (str/split ns #"\.")]
    (loop [path nil
           parts parts
           result []]
      (let [part (first parts)]
        (cond
          (nil? part)
          result

          (= "goog" part)
          (recur part (rest parts) result)

          :else
          (let [next-path (if path (str path "." part) part)
                token (str next-path " = goog.getObjectByName('" next-path "');")
                token (if path
                        token
                        (str "var " token))]
            (recur
              next-path
              (rest parts)
              (conj result token)))))
      )))

(defn js-for-local-names
  "pulls namespaces into the local scope, assumes goog is present

   this does not work in node since goog.provide exports to global
   goog.provide('cljs.core');
   cljs.core.something = true; // error since cljs does not exist

   so we generate
   var cljs = goog.getObjectByName('cljs');
   cljs.core = goog.getObjectByName('cljs.core');

   returns a vector of js statements
   "
  [namespaces]
  (->> namespaces
       (map comp/munge)
       (map str)
       (mapcat namespace-name->js-obj)
       (distinct)
       (into [])))

