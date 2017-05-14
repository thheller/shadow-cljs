(ns shadow.cljs.devtools.targets.npm-module
  (:refer-clojure :exclude (flush require))
  (:require [shadow.cljs.devtools.compiler :as comp]
            [shadow.cljs.build :as cljs]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [cljs.compiler :as cljs-comp]
            [cljs.source-map :as sm]
            [shadow.cljs.output :as output]
            [shadow.cljs.util :as util]
            [clojure.data.json :as json])
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

(defn src-suffix [state {:keys [provides] :as src}]
  (let [export
        (->> provides
             (map str)
             (sort)
             (reverse)
             (map cljs-comp/munge)
             (first))]

    (str "\nmodule.exports = " export ";\n")))

(defn cljs-env
  [state {:keys [runtime] :or {runtime :node} :as config}]
  (let [global
        (case runtime
          :node
          "global"
          :browser
          "window")]

    (str "var CLJS_ENV = {};\n"
         "var goog = CLJS_ENV.goog = {};\n"
         @(get-in state [:sources "goog/base.js" :input])
         "goog.global = process.browser ? window : global;\n"
         "goog.provide = function(name) { return goog.exportPath_(name, undefined, CLJS_ENV); };\n"
         "goog.require = function(name) { return true; };\n"
         "module.exports = CLJS_ENV;\n"
         )))

(defn flush
  [state mode
   {:keys [module-root module-name]
    :or {module-name "shadow-npm"
         module-root "./"}
    :as config}]

  (let [root
        (-> (io/file module-root)
            (.getCanonicalFile))

        output-dir
        (io/file root "node_modules" module-name)

        env
        (cljs-env state config)

        env-file
        (io/file output-dir "cljs_env.js")]

    (io/make-parents env-file)

    (spit env-file env)

    (doseq [src-name (:build-sources state)]
      (let [{:keys [name js-name input output requires source-map last-modified] :as src}
            (get-in state [:sources src-name])

            flat-name
            (flat-js-name js-name)

            target
            (io/file output-dir flat-name)]

        (when (or (not (.exists target))
                  (>= last-modified (.lastModified target))))

        (let [prefix
              (src-prefix state src)

              suffix
              (src-suffix state src)

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

          (spit target output)))))

  state)

(defn init [state mode {:keys [entries] :as config}]
  (let [entries
        (or entries
            (->> (:sources state)
                 (vals)
                 (remove :from-jar)
                 (map :provides)
                 (reduce set/union #{'cljs.core})))

        entries
        (conj entries 'shadow.cljs.devtools.client.console)]

    (-> state
        (assoc :source-map-comment false)
        (cljs/configure-module :default entries {}))))

(defn process
  [{::comp/keys [mode stage config] :as state}]
  (case stage
    :init
    (init state mode config)

    :flush
    (flush state mode config)

    state
    ))
