(ns shadow.build.cljs-bridge
  "things that connect the shadow.cljs world with the cljs world"
  (:require
    [clojure.string :as str]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [clojure.tools.reader :as reader]
    [clojure.tools.reader.reader-types :as readers]
    [cljs.tagged-literals :as tags]
    [cljs.analyzer :as ana]
    [cljs.env :as env]
    [cljs.externs :as externs]
    [cljs.analyzer :as cljs-ana]
    [cljs.compiler :as cljs-comp]
    [cljs.env :as cljs-env]
    [shadow.cljs.util :as util]
    [shadow.build.ns-form :as ns-form]
    [shadow.build.cljs-hacks]
    [shadow.build.data :as data])
  (:import (java.io PushbackReader StringReader)
           (java.util.concurrent Executors ExecutorService)))


(defn get-resource-info [resource-name content reader-features]
  {:pre [(string? content)]}
  (let [eof-sentinel (Object.)
        cljc? (util/is-cljc? resource-name)
        opts (merge
               {:eof eof-sentinel}
               (when cljc?
                 {:read-cond :allow :features reader-features}))
        rdr (StringReader. content)
        in (readers/indexing-push-back-reader (PushbackReader. rdr) 1 resource-name)]

    (binding [reader/*data-readers* tags/*cljs-data-readers*]
      (let [peek (reader/read opts in)]
        (if (identical? peek eof-sentinel)
          (throw (ex-info "file is empty" {:resource-name resource-name}))
          (-> (ns-form/parse peek)
              (assoc :cljc cljc?))
          )))))

(defn ensure-compiler-env
  [state]
  (cond-> state
    (nil? (:compiler-env state))
    (assoc :compiler-env
      ;; cljs.env/default-compiler-env force initializes a :js-dependency-index we are never going to use
      ;; this should always have the same structure
      {:cljs.analyzer/namespaces {'cljs.user {:name 'cljs.user}}
       :cljs.analyzer/constant-table {}
       :cljs.analyzer/data-readers {}
       :cljs.analyzer/externs nil
       :js-module-index {}
       :goog-names #{}
       :shadow/js-properties #{}
       :options (assoc (:compiler-options state)
                  ;; leave loading core data to the shadow.cljs.bootstrap loader
                  :dump-core false)})))

(defn nested-vals [map]
  (for [[_ ns-map] map
        [_ alias] ns-map]
    alias))

(defn register-ns-aliases
  "registers all resolved ns-aliases with the CLJS compiler env so it doesn't complain"
  ;; FIXME: not sure why I have to register these but not goog stuff? is there another hardcoded goog reference?
  [{:keys [ns-aliases str->sym] :as state}]
  (let [add-aliases-fn
        (fn [js-mod-index aliases]
          (reduce
            (fn [idx alias]
              (let [s (str alias)]
                ;; ignored clojure->cljs aliases, we only want the JS aliases
                (if (str/starts-with? s "cljs.")
                  idx
                  ;; FIXME: I don't quite get what this is supposed to be
                  ;; CLJS does {"React" {:name "module$node_modules$react$..."}}
                  ;; but we never have the "React" alias
                  (assoc idx (str alias) {:name alias
                                          :module-type :js}))))
            js-mod-index
            aliases))

        js-ns-idx
        (->> (:build-sources state)
             (map #(data/get-source-by-id state %))
             (filter #(contains? #{:js :shadow-js} (:type %)))
             (reduce
               (fn [idx {:keys [ns] :as rc}]
                 (assoc idx ns (select-keys rc [:ns :js-esm :js-commonjs :js-babel-esm :type :resource-id])))
               {})
             (doall))]

    (-> state
        (assoc-in [:compiler-env :shadow/js-namespaces] js-ns-idx)
        ;; FIXME: this includes clojure.* -> cljs.* aliases which should not be in js-module-index
        (update-in [:compiler-env :js-module-index] add-aliases-fn (vals ns-aliases))
        ;; str->sym maps all string requires to their symbol name
        (update-in [:compiler-env :js-module-index] add-aliases-fn (nested-vals str->sym)))
    ))

(defn register-goog-names [state]
  (let [goog-names
        (->> (:build-sources state)
             (map #(get-in state [:sources %]))
             (filter #(= :goog (:type %)))
             (map :provides)
             (reduce set/union #{}))]

    (assoc-in state [:compiler-env :goog-names] goog-names)))

(def ^:dynamic *in-compiler-env* false)

(defmacro with-compiler-env
  "compiler env is a rather big piece of dynamic state
   so we take it out when needed and put the updated version back when done
   doesn't carry the atom arround cause the compiler state itself should be persistent
   thus it should provide safe points

   the body should yield the updated compiler state and not touch the compiler env"
  [state & body]
  `(do (when *in-compiler-env*
         (throw (ex-info "already in compiler env" {})))
       (let [before# (:compiler-env ~state)
             dyn-env# (atom (assoc (:compiler-env ~state) ::state ~state))
             new-state# (binding [cljs-env/*compiler* dyn-env#
                                  *in-compiler-env* true]
                          ~@body)]

         (when-not (identical? before# (:compiler-env new-state#))
           (throw (ex-info "can't touch :compiler-env when in with-compiler-env" {})))

         (assoc new-state# :compiler-env (dissoc @dyn-env# ::state)))))

(defn get-build-state []
  (when-not *in-compiler-env*
    (throw (ex-info "not in compiler env" {})))
  (get @cljs-env/*compiler* ::state))

(defn swap-compiler-env!
  [state update-fn & args]
  (if *in-compiler-env*
    (do (swap! cljs-env/*compiler* (fn [current] (apply update-fn current args)))
        state)
    (update state :compiler-env (fn [current] (apply update-fn current args)))))

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
    (when-not (or (ana-is-cljs-def? lib sym)
                  (contains? (get-in @env/*compiler* [::ana/namespaces lib :macros]) sym)
                  ;; don't check refer when we have no analyzer data
                  (nil? (get-in @env/*compiler* [::ana/namespaces lib]))
                  (contains? use-macros sym))
      (error env :undeclared-ns-form {:type "var" :lib lib :sym sym}))))

(defn check-renames! [{:keys [renames rename-macros] :as ns-info}]
  (doseq [[rename-to rename-from] renames
          :let [rename-ns (symbol (namespace rename-from))
                rename-name (symbol (name rename-from))]]))

(comment
  (require '[clojure.pprint :refer (pprint)])

  (get-resource-info
    "demo/browser.cljs"
    (slurp (io/resource "demo/browser.cljs")))

  (pprint *1))

(comment
  ;; can't do externs inference in a pass since methods are analyzed twice
  ;; should see if that is really necessary
  (defn infer-externs-dot
    [env {:keys [field method target tag form] :as ast} opts]
    (let [prop (str (or method field))
          target-tag (:tag target)]

      (when (and (not (str/starts-with? prop "cljs$"))
                 (not= 'js target-tag)
                 (or (nil? target-tag)
                     (= 'any target-tag)))

        (prn [:infer-warning form])
        ;; (warning :infer-warning env {:warn-type :target :form form})
        ))
    ast)

  (defn infer-externs-invoke
    [env {:keys [tag f form] :as ast} opts]
    (prn [:invoke tag form])
    ast)

  (defn infer-externs [env {:keys [op] :as ast} opts]
    (case op
      :dot (infer-externs-dot env ast opts)
      :invoke (infer-externs-invoke env ast opts)
      ast)))
