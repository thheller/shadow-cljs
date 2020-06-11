(ns shadow.build.targets.gjs
  (:refer-clojure :exclude [flush compile])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [cljs.compiler :as comp]
            [shadow.build :as b]
            [shadow.build.log :as log]
            [shadow.build.api :as build-api]
            [shadow.build.output :as output]
            [shadow.build.closure :as closure]
            [shadow.build.targets.shared :as shared]
            [shadow.cljs.util :as util]
            [shadow.build.data :as data]
            [shadow.build.resource :as rc]
            [shadow.build.config :as config]
            [shadow.build.resolve :as resolve])
  (:import (java.lang ProcessBuilder$Redirect)))

;; Adds support for a new js-provider: `:gjs` which adds small functionality on
;; top of `:shadow` js-provider. This allows modules built into gjs, i.e.
;; gi.GLib, gi.Gtk, system etc to be imported idiomatically with string based
;; require specification.
;;
;; # Examples:
;;
;;    js:
;;        const Gtk = imports.gi.Gtk;
;;        const ByteArray = imports.ByteArray;
;;        const { Gtk, GLib } = imports.gi;
;;
;;    cljs:
;;        ["gjs.gi.Gtk" :as Gtk]
;;        ["gjs.byteArray" :as ByteArray]
;;        ["gjs.gi" :refer [Gtk GLib]]
;; ---------------------------------------------------------------------------------,
(defn gjs-builtin-resource
  "Serves gjs builtin resources like `system`, `GLib` etc.
  For example, to load builin system module, use:
    (require '[\"gjs.system\" :as system])
  "
  [require]
  (let [modules (-> (str/split require #"\.")
                    (rest))
        ns      (symbol require)]
    {:resource-id [::gjs require]
     :resource-name (str "gjs$" ns ".js")
     :output-name (str ns ".js")
     :global-ref true
     :type :js
     :cache-key [(System/currentTimeMillis)]
     :last-modified 0
     :ns ns
     :provides #{ns}
     :requires #{}
     :deps []
     :source (str "goog.provide(\"" require "\");\n"
                  require " = global.imports"
                  (str/join "" (for [m modules]
                                 (str "[\"" m "\"]")))
                  ";\n")}))

(defmethod resolve/find-resource-for-string* :gjs
  [state require-from require was-symbol?]
  (if (str/starts-with? require "gjs.")
    (gjs-builtin-resource require)
    (resolve/find-resource-for-string* (assoc-in state [:js-options :js-provider] :shadow)
                                       require-from
                                       require
                                       was-symbol?)))
;; ---------------------------------------------------------------------------------'

(defmethod log/event->str ::flush-unoptimized
  [{:keys [output-file] :as ev}]
  (str "Flush gjs script: " output-file))

(defmethod log/event->str ::flush-optimized
  [{:keys [output-file] :as ev}]
  (str "Flush optimized gjs script: " output-file))

(defn make-main-call-js [main-fn]
  {:pre [(symbol? main-fn)]}
  (str "\n
cljs.core.apply.cljs$core$IFn$_invoke$arity$2(" (comp/munge main-fn) ", window['ARGV'].slice(0));"))

(defn replace-goog-global [state]
  (update-in state [:sources output/goog-base-id :source]
    str/replace output/goog-global-snippet "goog.global = global;"))

(defn gjs-common-preambles
  []
  "
var global = window;

global.$CLJS = global;
global.shadow$provide = {};

// A rudimentary proxy for console.
window.console = {};
var SHADOW_GJS_create_logger_fn = function (level) {
    var fn = function (...args) {
       log([level].concat(args).map(x => String(x)).join(' '));
    };
    return fn;
};
console.log =  SHADOW_GJS_create_logger_fn('');
console.info = SHADOW_GJS_create_logger_fn('[INFO]');
console.warn = SHADOW_GJS_create_logger_fn('[WARN]');
console.debug = SHADOW_GJS_create_logger_fn('[DEBUG]');
console.error = SHADOW_GJS_create_logger_fn('[ERROR]');
")

(defn configure
  [state mode {:keys [main output-to] :as config}]
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

        gjs-config
        (assoc config
          :main-ns (symbol main-ns)
          :main-fn (symbol main-fn)
          :main main
          :output-to output-to)

        main-call
        (-> gjs-config :main (make-main-call-js))

        module-opts
        (-> config
            (select-keys [:prepend :append :prepend-js :append-js])
            (update :prepend #(str (gjs-common-preambles) %))
            (update :prepend #(str "(function(){\n" %))
            (cond->
              (not (false? (:hashbang config)))
              (update :prepend #(str "#!/usr/bin/env gjs\n" %)))
            (update :append-js str "\n" main-call)
            (update :append str "\n})();\n"))]

    (-> state
        (assoc :gjs-config gjs-config)
        (shared/set-output-dir mode config)

        (build-api/with-js-options
          {:target :gjs
           :js-provider :gjs
           :use-browser-overrides false
           :entry-keys ["main"]})

        ;; all semi-recent versions of gjs should be fine with es8
        ;; don't overwrite user choice though
        (cond->
            (nil? (get-in state [:shadow.build/config :compiler-options :output-feature-set]))
          (assoc-in [:compiler-options :output-feature-set] :es8))

        (build-api/configure-modules
          {:main
           (assoc module-opts
             :entries [(symbol main-ns)]
             :depends-on #{})})

        (assoc-in [:compiler-options :closure-defines 'cljs.core/*target*] "gjs")

        (cond->
            (:worker-info state)
            (shared/inject-node-repl config)

            (= :dev mode)
          (shared/inject-preloads :main config)
          )
        )))

(defn compile [state]
  (-> state
      (build-api/analyze-modules)
      (build-api/compile-sources)))

(defn optimize [state]
  (build-api/optimize state))

(defn gjs-unoptimized-preambles
  [rel-import-path]
  (str "
const SHADOW_IMPORT_PATH = function (rel_import_path) {
  const GLib = global.imports.gi.GLib;
  let progname = global.imports.system.programInvocationName;
  let dirname = GLib.path_get_dirname(progname);
  return GLib.build_pathv('/', [dirname, rel_import_path]);
}('" rel-import-path "');\n"))

(defn closure-defines
  [state]
  (str "\nglobal.CLOSURE_NO_DEPS = true;\n"
       "\nglobal.CLOSURE_DEFINES = " (output/closure-defines-json state) ";\n"))

(defn flush-unoptimized
  [{:keys [build-modules build-sources build-options compiler-options gjs-config polyfill-js] :as state}]
  (when (not= 1 (count build-modules))
    (throw (ex-info "gjs builds can only have one module!" {:tag ::output :build-modules build-modules})))

  (let [{:keys [cljs-runtime-path]}
        build-options

        {:keys [source-map]}
        compiler-options

        {:keys [output-to]}
        gjs-config]

    (output/flush-sources state)

    (util/with-logged-time
      [state {:type ::flush-unoptimized
              :output-file (.getAbsolutePath output-to)}]

      (let [{:keys [prepend append sources]}
            (first build-modules)

            output-dir-path
            (-> (data/output-file state cljs-runtime-path)
                (.getAbsoluteFile)
                (.toPath))

            output-to-path
            (-> output-to
                (.getAbsoluteFile)
                (.getParentFile)
                (.toPath))

            rel-path
            (-> (.relativize output-to-path output-dir-path)
                (rc/normalize-name))

            out
            (str/join "\n"
              [prepend

               (gjs-common-preambles)
               (gjs-unoptimized-preambles rel-path)

               (closure-defines state)

               ;; provides SHADOW_IMPORT and other things
               (slurp (io/resource "shadow/build/targets/gjs_bootstrap.js"))

               ;; import all other sources
               (->> sources
                    (map #(get-in state [:sources %]))
                    (map (fn [{:keys [provides output-name] :as src}]
                           (if (contains? provides 'goog)
                             (let [{:keys [js] :as out}
                                   (data/get-output! state src)]
                               (str (str/replace js #"goog.global = this;" "goog.global = global;")
                                    "\ngoog.provide = SHADOW_PROVIDE;"
                                    "\ngoog.require = SHADOW_REQUIRE;"
                                    (when (seq polyfill-js)
                                      (str "\n" polyfill-js
                                           "\nglobal.$jscomp = $jscomp;"))))
                             (str "SHADOW_IMPORT(" (pr-str output-name) ");"))))
                    (str/join "\n"))

               append])]

        (io/make-parents output-to)
        (spit output-to out))))

  ;; return unmodified state
  state)


(defn flush-optimized
  [{::closure/keys [modules] :keys [gjs-config] :as state}]
  (let [{:keys [output-to]} gjs-config]
    (util/with-logged-time
      [state {:type ::flush-optimized
              :output-file (.getAbsolutePath output-to)}]

      (when (not= 1 (count modules))
        (throw (ex-info "gjs builds can only have one module!" {:tag ::output :modules modules})))

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

(defn execute! [{:keys [gjs-config] :as state}]
  (when (not= 1 (-> state :build-modules count))
    (throw (ex-info "can only execute non modular builds" {})))

  (let [{:keys [output-to]}
        gjs-config

        script-args
        ["gjs"]

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

(s/def ::main shared/unquoted-qualified-symbol?)

(s/def ::target
  (s/keys
    :req-un
    [::main
     ::shared/output-to]
    :opt-un
    [::shared/output-dir]
    ))

(defmethod config/target-spec :gjs [_]
  (s/spec ::target))

(defmethod config/target-spec `process [_]
  (s/spec ::target))

(defn check-main-exists! [{:keys [compiler-env gjs-config] :as state}]
  (let [{:keys [main main-ns main-fn]} gjs-config]
    (when-not (get-in compiler-env [:cljs.analyzer/namespaces main-ns :defs main-fn])
      (throw (ex-info (format "The configured main \"%s\" does not exist!" main)
               {:tag ::main-not-found
                :main-ns main-ns
                :main-fn main-fn
                :main main})))
    state))

(defn flush [state mode config]
  (case mode
    :dev
    (flush-unoptimized state)
    :release
    (flush-optimized state)))

(defn process
  [{::b/keys [mode stage config] :as state}]
  (case stage
    :configure
    (configure state mode config)

    :compile-prepare
    (replace-goog-global state)

    :compile-finish
    (-> state
        (check-main-exists!)
        (cond->
          (shared/bootstrap-host-build? state)
          (shared/bootstrap-host-info)))

    :flush
    (flush state mode config)

    state
    ))
