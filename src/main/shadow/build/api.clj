(ns shadow.build.api
  (:require [cljs.analyzer :as cljs-ana]
            [clojure.java.io :as io]
            [shadow.build.resolve :as res]
            [shadow.build.classpath :as cp]
            [shadow.build.npm :as npm]
            [shadow.build.modules :as modules]
            [shadow.build.compiler :as impl]
            [shadow.cljs.util :as util]
            [shadow.build.cljs-bridge :as cljs-bridge]
            [shadow.build.closure :as closure]
            [shadow.build.data :as data]
            [shadow.build.output :as output]
            [shadow.build.log :as build-log]
            [shadow.build.resource :as rc]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            [shadow.build.resolve :as resolve]
            [shadow.build.babel :as babel])
  (:import (java.io File)
           (java.util.concurrent ExecutorService)))

(defn build-state? [build]
  (data/build-state? build))

(def default-compiler-options
  {:optimizations :none
   :static-fns true
   :elide-asserts false
   :closure-configurators []
   :infer-externs true
   :language-in :ecmascript5

   :closure-warnings
   {:check-types :off}

   :closure-threads
   (-> (Runtime/getRuntime)
       (.availableProcessors))

   :closure-defines
   {"goog.DEBUG" false
    "goog.LOCALE" "en"
    "goog.TRANSPILE" "never"
    "process.env.NODE_ENV" "development"}})

(def default-build-options
  {:print-fn :console
   :module-format :goog ;; or :js, maybe :es6 in the future?

   :asset-path "js"

   ;; during development this inludes goog/* sources into the module file
   ;; this is done to reduce the number of requests made
   ;; it only inlines goog sources since we don't need source maps for those
   :dev-inline-js true

   :cljs-runtime-path "cljs-runtime"

   :cache-level :all

   ;; namespaces that are known to rely on macro side-effects during compilation
   ;; they will not be cached themselves
   ;; and files that require them directly won't be cached to ensure that all
   ;; the expected side-effects can still occur.
   :cache-blockers
   '#{clara.rules
      clara.macros}
   })

(def default-js-options
  {:js-provider :require ;; :closure, :require, :include maybe :webpack, maybe something
   :generate-externs true
   :packages {}})

(defn init []
  (-> {:shadow.build/marker true

       :project-dir
       (-> (io/file "")
           (.getAbsoluteFile))

       :cache-dir
       (io/file "target" "shadow-cljs" "cache")

       :logger build-log/stdout

       :compiler-options
       default-compiler-options

       :build-options
       default-build-options

       :js-options
       default-js-options

       ;; FIXME: should these ever be configurable?
       :analyzer-passes
       [cljs-ana/infer-type]}
      (data/init)))

;; helper methods that validate their args, sort of
(defn with-npm [state npm]
  {:pre [(npm/service? npm)]}
  (assoc state :npm npm))

(defn with-babel [state babel]
  {:pre [(babel/service? babel)]}
  (assoc state :babel babel))

(defn with-classpath
  ([state]
   (with-classpath state))
  ([state cp]
   {:pre [(cp/service? cp)]}
   (assoc state :classpath cp)))

(defn with-logger [state logger]
  {:pre [(satisfies? build-log/BuildLog logger)]}
  (assoc state :logger logger))

(defn with-cache-dir [state cache-dir]
  {:pre [(util/is-file-instance? cache-dir)]}
  (assoc state :cache-dir cache-dir))

(defn with-executor [state executor]
  {:pre [(instance? ExecutorService executor)]}
  (assoc state :executor executor))

(defn with-build-options [state opts]
  (update state :build-options merge opts))

(defn merge-build-options [state opts]
  (update state :build-options merge opts))

(defn with-compiler-options [state opts]
  (update state :compiler-options merge opts))

(defn merge-compiler-options [state opts]
  (update state :compiler-options merge opts))

(defn with-js-options [state opts]
  (update state :js-options merge opts))

(defn enable-source-maps [state]
  (update state :compiler-options merge {:source-map "/dev/null"
                                         :source-map-comment true}))

(defn configure-modules [state modules]
  (modules/configure state modules))

(defn analyze-modules
  "takes module config and resolves all sources needed to compile"
  [state]
  (modules/analyze state))

(defn compile-sources
  "compiles a list of sources in dependency order
   compiles :build-sources if no list is given, use prepare-modules to make :build-sources"
  ([{:keys [build-sources] :as state}]
   (-> state
       (cljs-bridge/ensure-compiler-env)
       (cljs-bridge/register-ns-aliases)
       (cljs-bridge/register-goog-names)
       (impl/compile-all build-sources)))
  ([state source-ids]
   (-> state
       (assoc :build-sources source-ids)
       (compile-sources))))

(defn optimize [{:keys [classpath] :as state}]
  (let [deps-externs
        (cp/get-deps-externs classpath)]

    (-> state
        (assoc :deps-externs deps-externs)
        (closure/optimize))))

(defn check [state]
  (closure/check state))

(defn flush-unoptimized [state]
  (output/flush-unoptimized state))

(defn resolve-entries [state entries]
  (res/resolve-entries state entries))

(comment
  (defn compile-all-for-ns
    "compiles all files required by ns"
    [state ns]
    (let [state
          (prepare-compile state)

          deps
          (get-deps-for-entry state ns)]

      (-> state
          (assoc :build-sources deps)
          (compile-sources deps))
      ))

  (defn compile-all-for-src
    "compiles all files required by src name"
    [state src-name]
    (let [state
          (prepare-compile state)

          deps
          (get-deps-for-src state src-name)]

      (-> state
          (assoc :build-sources deps)
          (compile-sources deps))
      )))

(defn add-closure-configurator
  "adds a closure configurator 2-arity function that will be called before the compiler is invoked
   signature of the callback is (fn [compiler compiler-options])

   Compiler and CompilerOptions are mutable objects, the return value of the callback is ignored

   CLJS default configuration is done first, all configurators are applied later and may override
   any options.

   See:
   com.google.javascript.jscomp.Compiler
   com.google.javascript.jscomp.CompilerOptions"
  [state callback]
  (update state :closure-configurators conj callback))



(defn find-resources-affected-by
  "returns the set all resources and the immediate dependents of those sources
   intended for cache invalidation if one or more resources are changed
   a resource may change a function signature and we need to invalidate all namespaces
   that may be using that function to immediately get warnings"
  [state source-ids]
  (let [modified
        (set source-ids)]

    (->> (:sources state)
         (vals)
         (map :resource-id)
         (filter (fn [other-id]
                   (let [deps-of
                         (get-in state [:immediate-deps other-id])

                         uses-modified-resources
                         (set/intersection modified deps-of)]
                     (seq uses-modified-resources)
                     )))
         (into modified))))

(defn reset-resources [state source-ids]
  {:pre [(sequential? source-ids)]}

  (let [modified
        (set source-ids)

        all-deps-to-reset
        (find-resources-affected-by state source-ids)]
    (reduce data/remove-source-by-id state all-deps-to-reset)))

(defn- macro-test-fn [macros]
  (fn [{:keys [type macro-requires] :as src}]
    (when (= :cljs type)
      (seq (set/intersection macros macro-requires))
      )))

(defn build-affected-by-macros?
  "checks whether any sources currently used by the build use any of the given macro namespaces"
  [state macros]
  {:pre [(set? macros)]}
  (->> (:sources state)
       (vals)
       (some (macro-test-fn macros))))

(defn build-affected-by-macro?
  [state macro-ns]
  {:pre [(symbol? macro-ns)]}
  (build-affected-by-macros? state #{macro-ns}))

(defn find-resources-using-macros [state macros]
  (->> (:sources state)
       (vals)
       (filter (macro-test-fn macros))
       (map :resource-id)
       (into [])))

(defn reset-resources-using-macros [state macros]
  {:pre [(set? macros)]}
  (->> (find-resources-using-macros state macros)
       (reset-resources state)))



(defn add-sources-for-entries
  "utility function to simplify testing"
  [state entries]
  (let [[resolved resolved-state]
        (res/resolve-entries state entries)]
    ;; FIXME: maybe add resolved somewhere
    resolved-state
    ))