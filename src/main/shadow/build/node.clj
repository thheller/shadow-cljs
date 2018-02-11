(ns shadow.build.node
  (:refer-clojure :exclude [flush compile])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [cljs.compiler :as comp]
            [shadow.build.log :as log]
            [shadow.build.api :as build-api]
            [shadow.build.output :as output]
            [shadow.build.closure :as closure]
            [shadow.cljs.util :as util]
            [shadow.build.data :as data]
            [shadow.build.resource :as rc])
  (:import (java.lang ProcessBuilder$Redirect)))

(defmethod log/event->str ::flush-unoptimized
  [{:keys [output-file] :as ev}]
  (str "Flush node script: " output-file))

(defmethod log/event->str ::flush-optimized
  [{:keys [output-file] :as ev}]
  (str "Flush optimized node script: " output-file))

(defn make-main-call-js [main-fn]
  {:pre [(symbol? main-fn)]}
  (str "\ncljs.core.apply.cljs$core$IFn$_invoke$arity$2(" (comp/munge main-fn) ", process.argv.slice(2));"))

(defn configure
  [state {:keys [main output-to] :as opts}]
  (let [main-ns
        (namespace main)

        [main-ns main-fn]
        (if (nil? main-ns)
          [(name main) "main"]
          [main-ns (name main)])

        output-to
        (io/file output-to)

        output-name
        (.getName output-to)

        main
        (symbol main-ns main-fn)

        node-config
        (assoc opts
          :main-ns (symbol main-ns)
          :main-fn (symbol main-fn)
          :main main
          :output-to output-to)

        main-call
        (-> node-config :main (make-main-call-js))

        module-opts
        (-> opts
            (select-keys [:prepend :append :prepend-js :append-js])
            (update :prepend #(str "(function(){\n" %))
            (cond->
              (not (false? (:hashbang opts)))
              (update :prepend #(str "#!/usr/bin/env node\n" %)))
            (update :append-js str "\n" main-call)
            (update :append str "\n})();\n"))]

    (-> state
        (assoc :node-config node-config)
        (build-api/configure-modules
          {:main
           (assoc module-opts
             :entries [(symbol main-ns)]
             :depends-on #{})})
        )))

(defn compile [state]
  (-> state
      (build-api/analyze-modules)
      (build-api/compile-sources)))

(defn optimize [state]
  (build-api/optimize state))

(defn closure-defines
  [state]
  (str "\nglobal.CLOSURE_NO_DEPS = true;\n"
       "\nglobal.CLOSURE_DEFINES = " (output/closure-defines-json state) ";\n"))

(defn flush-unoptimized
  [{:keys [build-modules build-sources build-options compiler-options node-config] :as state}]
  (when (not= 1 (count build-modules))
    (throw (ex-info "node builds can only have one module!" {:tag ::output :build-modules build-modules})))

  (let [{:keys [cljs-runtime-path]}
        build-options

        {:keys [source-map]}
        compiler-options

        {:keys [output-to]}
        node-config]

    (output/flush-sources state)

    (util/with-logged-time
      [state {:type ::flush-unoptimized
              :output-file (.getAbsolutePath output-to)}]

      (let [{:keys [prepend append sources]}
            (first build-modules)

            rel-path
            (-> output-to
                (.getParentFile)
                (.toPath)
                (.relativize (-> (data/output-file state cljs-runtime-path)
                                 (.toPath)))
                (.toString)
                (rc/normalize-name))

            out
            (str/join "\n"
              [prepend

               ;; this is here and not in boostrap since defines already accesses them
               (str "var SHADOW_IMPORT_PATH = " (pr-str rel-path) ";")

               "global.$CLJS = global;"

               (when source-map
                 (str "try {"
                      "require('source-map-support').install();"
                      "} catch (e) {"
                      "console.warn('no \"source-map-support\" (run \"npm install source-map-support --save-dev\" to get it)');"
                      "}"))

               ;; this means they rely on goog.global = this AND fn.call(SHADOW_ENV, ...)
               ;; I eventually want to turn the "this" of shadow imports into the module
               ;; to match what node does.
               (closure-defines state)

               ;; provides SHADOW_IMPORT and other things
               (slurp (io/resource "shadow/cljs/node_bootstrap.txt"))

               ;; import all other sources
               (->> sources
                    (map #(get-in state [:sources %]))
                    (map (fn [{:keys [provides output-name] :as src}]
                           (if (contains? provides 'goog)
                             (let [{:keys [js] :as out}
                                   (data/get-output! state src)]
                               (str (str/replace js #"goog.global = this;" "goog.global = global;")
                                    "\ngoog.provide = SHADOW_PROVIDE;"
                                    "\ngoog.require = SHADOW_REQUIRE;"))
                             (str "SHADOW_IMPORT(" (pr-str output-name) ");"))))
                    (str/join "\n"))

               #_(when-some [main (:main node-config)]
                   (let [root
                         (-> (str main)
                             (comp/munge))
                         root
                         (subs root 0 (str/index-of root "."))]
                     (str "var " root " = SHADOW_ENV." root ";")))

               append])]

        (io/make-parents output-to)
        (spit output-to out))))

  ;; return unmodified state
  state)


(defn flush-optimized
  [{::closure/keys [modules] :keys [node-config] :as state}]
  (let [{:keys [output-to]} node-config]
    (util/with-logged-time
      [state {:type ::flush-optimized
              :output-file (.getAbsolutePath output-to)}]

      (when (not= 1 (count modules))
        (throw (ex-info "node builds can only have one module!" {:tag ::output :modules modules})))

      (when-not (seq modules)
        (throw (ex-info "flush before optimize?" {})))

      (-> state
          (assoc-in [:build-options :output-dir] (-> output-to
                                                     (.getCanonicalFile)
                                                     (.getParentFile)))
          (assoc-in [::closure/modules 0 :output-name] (.getName output-to))
          (output/flush-optimized))))

  state)

(defmethod log/event->str ::execute!
  [{:keys [args]}]
  (format "Execute: %s" (pr-str args)))

(defn execute! [{:keys [node-config] :as state}]
  (when (not= 1 (-> state :build-modules count))
    (throw (ex-info "can only execute non modular builds" {})))

  (let [{:keys [output-to]}
        node-config

        script-args
        ["node"]

        pb
        (doto (ProcessBuilder. script-args)
          (.directory nil)
          (.redirectOutput ProcessBuilder$Redirect/INHERIT)
          (.redirectError ProcessBuilder$Redirect/INHERIT))]

    ;; not using this because we only get output once it is done
    ;; I prefer to see progress
    ;; (prn (apply shell/sh script-args))

    (util/with-logged-time
      [state {:type ::execute!
              :args script-args}]
      (let [proc
            (.start pb)]

        (let [out (.getOutputStream proc)]
          (io/copy (io/file output-to) out)
          (.close out))

        ;; FIXME: what if this doesn't terminate?
        (let [exit-code (.waitFor proc)]
          (assoc state ::exit-code exit-code))))))

