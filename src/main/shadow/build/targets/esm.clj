(ns shadow.build.targets.esm
  (:refer-clojure :exclude (flush require resolve))
  (:require
    [clojure.spec.alpha :as s]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [cljs.compiler :as cljs-comp]
    [shadow.build :as build]
    [shadow.build.modules :as modules]
    [shadow.build.api :as build-api]
    [shadow.build.output :as output]
    [shadow.build.targets.shared :as shared]
    [shadow.build.targets.browser :as browser]
    [shadow.build.config :as config]
    [shadow.build.node :as node]
    [shadow.build.js-support :as js-support]
    [shadow.build.closure :as closure]
    [shadow.cljs.util :as util]
    [shadow.build.data :as data]
    [shadow.build.async :as async]
    [clojure.set :as set]))

(defn str-prepend [x y]
  (str y x))

(s/def ::runtime #{:node :browser :react-native :custom})

(s/def ::exports
  (s/map-of simple-ident? qualified-symbol? :min-count 1))

;; will just be added as is (useful for comments, license, ...)
(s/def ::prepend string?)
(s/def ::append string?)

;; these go through closure optimized, should be valid js
(s/def ::prepend-js string?)
(s/def ::append-js string?)

(s/def ::depends-on
  (s/coll-of keyword? :kind set?))

(s/def ::module
  (s/and
    ;; {init-fn foo.bar/init} should fail
    (s/map-of keyword? any?)
    (s/keys
      :opt-un
      [::exports
       ::depends-on
       ::prepend
       ::prepend-js
       ::append-js
       ::append])))

(s/def ::modules
  (s/map-of
    simple-keyword?
    ::module
    :min-count 1))

(s/def ::target
  (s/keys
    :req-un
    [::shared/output-dir
     ::modules]
    :opt-un
    [::runtime
     ::shared/devtools]))

(defmethod config/target-spec :esm [_]
  (s/spec ::target))

(defn add-mod-imports-exports [state]
  (update state ::modules/config
    (fn [modules]
      (reduce-kv
        (fn [modules mod-id {:keys [module-id default exports depends-on] :as mod}]
          (assoc modules
            mod-id
            (-> mod
                (cond->
                  default
                  (update :prepend str "const $APP = {}; const shadow$provide = {};\n"))

                (util/reduce->
                  (fn [state other-mod-id]
                    (let [other-name (get-in modules [other-mod-id :output-name])]
                      (update state :prepend str
                        "import "
                        ;; only import { $APP } from ... once, otherwise just import from ...
                        ;; all modules re-export it so they can be loaded in any order
                        ;; and always end up getting the $APP from the default module
                        (when (= other-mod-id (first depends-on))
                          "{ $APP, shadow$provide, $jscomp } from")
                        " \"./" other-name "\";\n")))
                  depends-on)

                (util/reduce-kv->
                  (fn [state export-name export-sym]
                    (let [bridge-name (str "ex_" (cljs-comp/munge (name module-id)) "_" (name export-name))]

                      (-> state
                          ;; when advanced sees export ... it just remove it
                          ;; so we must keep that out of the :advanced compiled code
                          ;; by bridging through a local let which is declared in externs
                          ;; so closure doesn't remove it. the :advanced part then just assigns it
                          (update :module-externs conj bridge-name)
                          (update :prepend str "let " bridge-name ";\n")
                          (update :append-js str "\n" bridge-name " = " (cljs-comp/munge export-sym) ";")
                          (update :append str "\nexport { " bridge-name " as " (name export-name) " };")
                          )))
                  exports)
                (update :append str "\nexport { $APP, shadow$provide, $jscomp };\n")
                )))
        modules
        modules))))

(defn rewrite-modules
  [{:keys [worker-info]
    ::build/keys [mode config]
    :as state}]

  (let [{:keys [modules runtime devtools] :or {runtime :browser}} config
        default-module (browser/pick-default-module-from-config modules)
        {:keys [enabled]} devtools
        enabled? (not (false? enabled))
        build-worker? (and enabled? (= :dev mode) worker-info)]
    (reduce-kv
      (fn [mods module-id {:keys [entries exports init-fn preloads] :as module-config}]
        (let [default?
              (= default-module module-id)

              entries
              (->> exports
                   (vals)
                   (map namespace)
                   (map symbol)
                   (distinct)
                   (concat (or entries []))
                   (into []))

              module-config
              (-> module-config
                  (assoc :force-append true
                         :force-prepend (= :dev mode)
                         :module-externs #{}
                         :entries entries
                         :default default?)
                  (cond->
                    ;; closure will try to rewrite dynamic import() calls
                    ;; but its just a regular function so we alias and extern it
                    ;; so closure doesn't rename it.
                    default?
                    (update :module-externs conj "shadow_esm_import")

                    ;; REPL client - only for watch (via worker-info), not compile
                    ;; this needs to be in base module
                    (and default? build-worker?)
                    (update :entries shared/prepend '[shadow.cljs.devtools.client.env])

                    (and build-worker? default? (= :browser runtime))
                    (update :entries shared/prepend '[shadow.cljs.devtools.client.browser])

                    (and build-worker? default? (= :custom runtime) (:client-ns devtools))
                    (update :entries shared/prepend [(:client-ns devtools)])

                    (and (seq preloads) (= :dev mode))
                    (update :entries shared/prepend preloads)

                    ;; global :devtools :preloads
                    (and default? (= :dev mode))
                    (browser/inject-preloads state config)

                    init-fn
                    (browser/merge-init-fn init-fn)

                    ;; DEVTOOLS console, it is prepended so it loads first in case anything wants to log
                    (and default? (= :dev mode) (= :browser runtime))
                    (browser/inject-devtools-console state config)))]

          (assoc mods module-id module-config)))
      {}
      modules)))

(defn configure-modules
  [{::build/keys [mode] :as state}]
  (let [modules (rewrite-modules state)]
    (-> state
        (build-api/configure-modules modules)
        (cond->
          (= :release mode)
          (add-mod-imports-exports)))))

(defn configure
  [{::build/keys [config mode] :as state}]
  (let [{:keys [runtime output-dir]} config]
    (-> state
        (build-api/with-build-options {})
        (cond->
          (not (get-in config [:js-options :js-provider]))
          (build-api/with-js-options {:js-provider :shadow}))

        (configure-modules)

        (assoc-in [:compiler-options :emit-use-strict] false)

        (cond->
          (not (get-in config [:compiler-options :output-feature-set]))
          (build-api/with-compiler-options {:output-feature-set :es2020})

          output-dir
          (build-api/with-build-options {:output-dir (io/file output-dir)})

          (= :node runtime)
          (node/set-defaults)

          (= :release mode)
          (assoc-in [:compiler-options :rename-prefix-namespace] "$APP")

          (and (= :dev mode) (:worker-info state))
          (shared/merge-repl-defines config)
          ))))

(defn flush-source
  [state src-id]
  (let [{:keys [resource-name output-name last-modified] :as src}
        (data/get-source-by-id state src-id)

        {:keys [js compiled-at] :as output}
        (data/get-output! state src)

        js-file
        (if-some [sub-path (get-in state [:build-options :cljs-runtime-path])]
          (data/output-file state sub-path output-name)
          (data/output-file state output-name))]

    ;; skip files we already have
    (when (or true
              (not (.exists js-file))
              (zero? last-modified)
              ;; js is not compiled but maybe modified
              (> (or compiled-at last-modified)
                 (.lastModified js-file)))

      (io/make-parents js-file)

      (util/with-logged-time
        [state {:type :flush-source
                :resource-name resource-name}]

        (let [prepend
              (str "import \"./cljs_env.js\";\n")

              output
              (str prepend
                   js
                   (output/generate-source-map state src output js-file prepend))]
          (spit js-file output))))))

(defn flush-unoptimized-module
  [{:keys [worker-info build-modules] :as state}
   {:keys [module-id output-name exports prepend append sources depends-on] :as mod}]

  (let [target (data/output-file state output-name)]

    (doseq [src-id sources]
      (async/queue-task state #(flush-source state src-id)))

    (let [module-imports
          (when (seq depends-on)
            (->> (reverse build-modules)
                 (filter #(contains? depends-on (:module-id %)))
                 (map :output-name)
                 (map #(str "import \"./" % "\";"))
                 (str/join "\n")))

          imports
          (->> sources
               (remove #{output/goog-base-id})
               (map #(data/get-source-by-id state %))
               (map (fn [{:keys [output-name] :as rc}]

                      (str "import \"./cljs-runtime/" output-name "\";\n"
                           "SHADOW_ENV.setLoaded(" (pr-str output-name) ");"
                           )))
               (str/join "\n"))

          exports
          (->> exports
               (map (fn [[export sym]]
                      (let [export-name (name export)]
                        (if (= export-name "default")
                          (str "export default " (cljs-comp/munge sym) ";")
                          (str "export let " export-name " = " (cljs-comp/munge sym) ";")))))
               (str/join "\n"))

          out
          (str prepend "\n"
               module-imports "\n"
               imports "\n"
               exports "\n"
               append "\n"
               (when (and worker-info (not (false? (get-in state [::build/config :devtools :enabled]))))
                 (str "shadow.cljs.devtools.client.env.module_loaded(\"" (name module-id) "\");")))]
      (io/make-parents target)
      (spit target out))

    state))

;; closure library sometimes changes how goog.global is assigned
;; this may cause warnings in some tools because of the remaining
;; this reference which is undefined in ES modules
;; so instead we want to replace it with something that just uses globalThis

;; starting here
;;   goog.global =
;;     // Check `this` first for backwards compatibility.
;;     // Valid unless running as an ES module or in a function wrapper called
;;     //   without setting `this` properly.
;;     // Note that base.js can't usefully be imported as an ES module, but it may
;;     // be compiled into bundles that are loadable as ES modules.
;;     this ||
;;     // https://developer.mozilla.org/en-US/docs/Web/API/Window/self
;;     // For in-page browser environments and workers.
;;     self;
;; ending with the next jsdoc block
;;   /**


(defn replace-goog-global* [src]
  ;; not using regexp due to multiline assignment
  ;; and other weirdness it may contain. just hoping they never use /** comments during assignment
  (let [needle-start "goog.global ="
        needle-end "/**"

        start-idx (str/index-of src needle-start)

        _ (when-not start-idx
            (throw (ex-info "didn't find goog.global assignment in goog/base.js" {})))

        end-idx (str/index-of src needle-end start-idx)

        before (subs src 0 start-idx)
        after (subs src end-idx (count src))]

    (str before "goog.global = globalThis;\n" after)
    ))

(comment
  (tap> (replace-goog-global* (slurp (io/resource "goog/base.js")))))

(defn replace-goog-global [state]
  ;; the global must be overriden in goog/base.js since it contains some
  ;; goog.define(...) which would otherwise be exported to "this"
  ;; but we need it on $CLJS, can't just reassign it after

  ;; doing this on the source directly before any compilation because
  ;; for release builds the source is passed directly to the compiler
  ;; and is not taken from :output
  (if (::base-modified state)
    state
    (-> state
        (assoc ::base-modified true)
        (update-in [:sources output/goog-base-id :source] replace-goog-global*))))

(defn js-module-env
  [{:keys [polyfill-js]
    ::build/keys [config]
    :as state}]

  (->> ["globalThis.CLOSURE_DEFINES = " (output/closure-defines-json state) ";"
        "globalThis.CLOSURE_NO_DEPS = true;"
        (get-in state [:output output/goog-base-id :js])
        "globalThis.goog = goog;"
        "globalThis.shadow$provide = {};"
        "globalThis.shadow_esm_import = function(x) { return import(x.startsWith(\"./\") ? \".\" + x : x); }"
        "let $CLJS = globalThis.$CLJS = globalThis;"
        (slurp (io/resource "shadow/boot/esm.js"))

        (when (seq polyfill-js)
          (str polyfill-js "\n"
               "globalThis.$jscomp = $jscomp;\n"))]
       (remove nil?)
       (str/join "\n")))

(defn flush-dev [{::build/keys [config] :keys [build-modules] :as state}]
  (when-not (seq build-modules)
    (throw (ex-info "flush before compile?" {})))

  (util/with-logged-time
    [state {:type :flush-unoptimized}]

    (let [env-file (data/output-file state "cljs-runtime" "cljs_env.js")]
      (io/make-parents env-file)
      (spit env-file (js-module-env state)))

    (reduce
      (fn [state mod]
        (flush-unoptimized-module state mod))
      state
      build-modules))
  state)

(defn inject-polyfill-js [{:keys [polyfill-js] :as state}]
  (update-in state [::closure/modules 0 :prepend] str
    (if (seq polyfill-js)
      polyfill-js
      "const $jscomp = {};\n")))

(defn setup-imports [state]
  (update state :build-modules
    (fn [modules]
      (->> modules
           (map
             (fn [{:keys [sources] :as mod}]
               (let [sources
                     (->> sources
                          (map #(data/get-source-by-id state %))
                          (filter ::js-support/import-shim))

                     externs
                     (into #{} (map :import-alias) sources)

                     imports
                     (->> sources
                          (map (fn [{:keys [js-import import-alias]}]
                                 (str "import * as " import-alias " from \"" js-import "\";")))
                          (str/join "\n"))]

                 (-> mod
                     (update :module-externs set/union externs)
                     (update :prepend str-prepend (str imports "\n"))
                     (cond->
                       ;; only create shadow_esm_import if shadow.esm was required anywhere
                       ;; needs to be created in all modules since it must be module local
                       (get-in state [:sym->id 'shadow.esm])
                       (update :prepend str "const shadow_esm_import = function(x) { return import(x) };\n"))
                     ))))
           (vec)))))

;; in dev all imports must happen in the prepend
;; can't do it in the pseudo-module since that evals after all the sources in
;; it were loaded and that may lead to undefined errors since the module sets the
;; globalThis alias too late. in release builds its just a regular prepend
(defn setup-imports-dev [{:keys [build-modules] :as state}]
  (reduce-kv
    (fn [state idx {:keys [module-id sources] :as mod}]
      (let [sources
            (->> sources
                 (map #(data/get-source-by-id state %))
                 (filter ::js-support/import-shim))

            imports
            (->> sources
                 (map (fn [{:keys [import-alias js-import]}]
                        (str "import * as " import-alias " from \"" js-import "\";\n"
                             "globalThis." import-alias " = " import-alias ";")))
                 (str/join "\n"))

            prepend-id
            [:shadow.build.modules/prepend module-id]]

        (update-in state [:sources prepend-id :source] str imports "\n")))
    state
    build-modules))

(defn process
  [{::build/keys [mode stage] :as state}]
  (cond
    (= stage :configure)
    (configure state)

    (= stage :compile-prepare)
    (-> state
        (replace-goog-global)
        (cond->
          (= :dev mode)
          (setup-imports-dev)

          (= :release mode)
          (setup-imports)))

    (= stage :flush)
    (case mode
      :dev
      (flush-dev state)
      :release
      (-> state
          (inject-polyfill-js)
          (output/flush-optimized)))

    :else
    state))
