(ns shadow.cljs.devtools.targets.npm-module
  (:refer-clojure :exclude (flush require))
  (:require [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [cljs.compiler :as cljs-comp]
            [cljs.source-map :as sm]
            [cljs.analyzer :as ana]
            [shadow.cljs.output :as output]
            [shadow.cljs.util :as util]
            [clojure.data.json :as json]
            [shadow.cljs.devtools.targets.shared :as shared]
            [shadow.cljs.repl :as repl]
            [shadow.cljs.devtools.targets.browser :as browser])
  (:import (java.io StringReader BufferedReader)
           (java.util Base64)))

(defn flat-js-name [js-name]
  (str/replace js-name #"/" "."))

(defn get-root [sym]
  (let [s (cljs-comp/munge (str sym))]
    (if-let [idx (str/index-of s ".")]
      (subs s 0 idx)
      s)))

(defn src-prefix [state {:keys [type ns name provides requires] :as src}]
  (let [roots
        (->> requires
             (map get-root)
             (concat ["goog"])
             (into #{}))]

    (str "var CLJS_ENV = require(\"./cljs_env\");\n"
         ;; the only actually global var goog sometimes uses that is not on goog.global
         ;; actually only: goog/promise/thenable.js goog/proto2/util.js?
         "var COMPILED = false;\n"
         (->> requires
              (remove #{'goog})
              (map (fn [sym]
                     (get-in state [:provide->source sym])))
              (distinct)
              (map (fn [src-name]
                     (let [{:keys [js-name]}
                           (get-in state [:sources src-name])]
                       (str "require(\"./" (flat-js-name js-name) "\");"))))
              (str/join "\n"))
         "\n"
         ;; require roots will exist
         (->> roots
              (map (fn [root]
                     (str "var " root "=CLJS_ENV." root ";")))
              (str/join "\n"))
         "\n"
         ;; provides may create new roots
         (->> provides
              (map get-root)
              (remove roots)
              (map (fn [root]
                     (str "var " root "=CLJS_ENV." root " || (CLJS_ENV." root " = {});")))
              (str/join "\n"))
         "\n")))

(defn make-constant-table [{:keys [build-sources] :as state}]
  ;; cannot use this cause it doesn't work with incremental compiles
  ;; (get-in state [:compiler-env ::ana/constant-table])
  ;; instead must rebuild it from all compiled files since the info about constants
  ;; is contained in each individual ns cache

  (let [constants
        (->> build-sources
             (map #(get-in state [:sources %]))
             (filter #(= :cljs (:type %)))
             (map :ns)
             (map #(get-in state [:compiler-env ::ana/namespaces % ::ana/constants :seen]))
             (reduce set/union #{}))]

    (->> constants
         (map (fn [x] {x (ana/gen-constant-id x)}))
         (into {}))
    ))

(defn src-suffix [{:keys [build-sources] :as state} mode {:keys [ns provides] :as src}]
  ;; export the shortest name always, some goog files have multiple provides
  (let [export
        (->> provides
             (map str)
             (sort)
             (map cljs-comp/munge)
             (first))]

    ;; emit all constants into ./cljs.core.js
    ;; FIXME: lazy, create the proper cljs.core.constants.js
    (str (when (and (= :release mode) (= ns 'cljs.core))
           (let [table (make-constant-table state)]
             (with-out-str
               (cljs-comp/emit-constants-table table))))
         "\nmodule.exports = " export ";\n")))

(defn cljs-env
  [state {:keys [runtime] :or {runtime :node} :as config}]
  (str "var CLJS_ENV = {};\n"
       "var CLJS_GLOBAL = process.browser ? window : global;\n"
       ;; closure accesses these defines via goog.global.CLOSURE_DEFINES
       "var CLOSURE_DEFINES = CLJS_ENV.CLOSURE_DEFINES = " (output/closure-defines-json state) ";\n"
       "CLJS_GLOBAL.CLOSURE_NO_DEPS = true;\n"
       "var goog = CLJS_ENV.goog = {};\n"
       @(get-in state [:sources "goog/base.js" :input])
       (slurp (io/resource "shadow/cljs/devtools/targets/npm_module_goog_overrides.js"))
       "\nmodule.exports = CLJS_ENV;\n"
       ))


(defn flush
  [{::comp/keys [build-info] :as state} mode
   {:keys [module-root module-name]
    :or {module-name "shadow-cljs"
         module-root "./"}
    :as config}]

  ;; otherwise re-emit everything until there is a stable strategy for caching
  ;; need to work out some details of :emit-constants since we usually use closure for that
  (let [root
        (-> (io/file module-root)
            (.getCanonicalFile))

        output-dir
        (io/file root "node_modules" module-name)

        env-file
        (io/file output-dir "cljs_env.js")]

    (util/with-logged-time [state {:type :npm-flush :output-path (.getAbsolutePath output-dir)}]
      (io/make-parents env-file)

      (spit env-file (cljs-env state config))

      (doseq [src-name (:build-sources state)]
        (let [{:keys [name js-name input output requires source-map last-modified] :as src}
              (get-in state [:sources src-name])

              flat-name
              (flat-js-name js-name)

              target
              (io/file output-dir flat-name)]

          (when (or (:release mode)
                    (contains? (:compiled build-info) name)
                    (not (.exists target))
                    (>= last-modified (.lastModified target)))

            (let [prefix
                  (src-prefix state src)

                  suffix
                  (src-suffix state mode src)

                  sm-text
                  (when source-map
                    (let [sm-opts
                          {:lines (output/line-count output)
                           :file flat-name
                           :preamble-line-count (output/line-count prefix)
                           :sources-content [@input]}

                          source-map-v3
                          (-> {flat-name source-map}
                              (sm/encode* sm-opts)
                              (assoc "sources" [name]))

                          source-map-json
                          (json/write-str source-map-v3)

                          b64
                          (-> (Base64/getEncoder)
                              (.encodeToString (.getBytes source-map-json)))]

                      (str "\n//# sourceMappingURL=data:application/json;charset=utf-8;base64," b64 "\n")
                      ))

                  output
                  (str prefix output suffix sm-text)]

              (spit target output)))))))

  state)

(defn release-mode [state config]
  (prn [:release-mode])
  (update state :compiler-options merge {:optimize-constants true
                                         :emit-constants true}))

(defn init [state mode {:keys [runtime entries] :as config}]
  (let [entries
        (or entries
            (->> (:sources state)
                 (vals)
                 (remove :from-jar)
                 (map :provides)
                 (reduce set/union #{})))

        entries
        (conj entries 'cljs.core)]

    (-> state
        (assoc :source-map-comment false
               ::comp/skip-optimize true)

        (cljs/configure-module :default entries {})

        (cond->
          (= :dev mode)
          (repl/setup)

          ;; since we don't use closure set any
          ;; cljs options that could benefit us
          (= :release mode)
          (release-mode config))

        (cond->
          (and (:worker-info state) (= :dev mode) (= :node runtime))
          (shared/inject-node-repl config)
          (and (:worker-info state) (= :dev mode) (= :browser runtime))
          (browser/inject-devtools config)
          ))))

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))
