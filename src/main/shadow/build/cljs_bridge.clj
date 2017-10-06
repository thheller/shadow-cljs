(ns shadow.build.cljs-bridge
  "things that connect the shadow.cljs world with the cljs world"
  (:require [cljs.analyzer :as cljs-ana]
            [cljs.compiler :as cljs-comp]
            [cljs.env :as cljs-env]

            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as readers]
            [cljs.tagged-literals :as tags]

            [shadow.cljs.util :as util]
            [shadow.build.ns-form :as ns-form]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [cljs.analyzer :as ana]
            [cljs.env :as env])
  (:import (java.io PushbackReader StringReader)
           (java.util.concurrent Executors ExecutorService)))


(defn get-resource-info [url]
  (let [eof-sentinel (Object.)
        name (str url)
        cljc? (util/is-cljc? (.toString url))
        opts (merge
               {:eof eof-sentinel}
               (when cljc?
                 {:read-cond :allow :features #{:cljs}}))
        rdr (StringReader. (slurp url))
        in (readers/indexing-push-back-reader (PushbackReader. rdr) 1 name)]

    (binding [reader/*data-readers* tags/*cljs-data-readers*]
      (let [peek (reader/read opts in)]
        (if (identical? peek eof-sentinel)
          (throw (ex-info "file is empty" {:name name}))
          (-> (ns-form/parse peek)
              (assoc
                :url url
                :cljc cljc?))
          )))))

(defn ensure-compiler-env
  [state]
  (cond-> state
    (nil? (:compiler-env state))
    (assoc :compiler-env @(cljs-env/default-compiler-env (assoc (:compiler-options state)
                                                           ;; leave loading core data to the shadow.cljs.bootstrap loader
                                                           :dump-core false
                                                           )))))

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
              ;; FIXME: I don't quite get what this is supposed to be
              ;; CLJS does {"React" {:name "module$node_modules$react$..."}}
              ;; but we never have the "React" alias
              (assoc idx (str alias) {:name (str alias)
                                      :module-type :commonjs}))
            js-mod-index
            aliases))]

    (-> state
        ;; FIXME: this includes clojure.* -> cljs.* aliases which should not be in js-module-index
        (update-in [:compiler-env :js-module-index] add-aliases-fn (vals ns-aliases))
        ;; str->sym maps all string requires to their symbol name
        (update-in [:compiler-env :js-module-index] add-aliases-fn (nested-vals str->sym)))
    ))

(def ^:dynamic *in-compiler-env* false)

(defmacro with-compiler-env
  "compiler env is a rather big piece of dynamic state
   so we take it out when needed and put the updated version back when done
   doesn't carry the atom arround cause the compiler state itself should be persistent
   thus it should provide safe points

   the body should yield the updated compiler state and not touch the compiler env

   I don't touch the compiler env itself yet at all, might do for some metadata later"
  [state & body]
  `(do (when *in-compiler-env*
         (throw (ex-info "already in compiler env" {})))
       (let [dyn-env# (atom (:compiler-env ~state))
             new-state# (binding [cljs-env/*compiler* dyn-env#
                                  *in-compiler-env* true]
                          ~@body)]
         (assoc new-state# :compiler-env @dyn-env#))))

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
                  (nil? (get-in @env/*compiler* [::ana/namespaces lib])))
      (error env :undeclared-ns-form {:type "var" :lib lib :sym sym}))))

(defn check-renames! [{:keys [renames rename-macros] :as ns-info}]
  (doseq [[rename-to rename-from] renames
          :let [rename-ns (symbol (namespace rename-from))
                rename-name (symbol (name rename-from))]]))

(comment
  (require '[clojure.pprint :refer (pprint)])

  (get-resource-info
    (io/resource "demo/browser.cljs"))

  (pprint *1))

