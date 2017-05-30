(ns shadow.cljs.output
  (:require [clojure.java.io :as io]
            [cljs.source-map :as sm]
            [shadow.cljs.util :as util]
            [clojure.string :as str]
            [cljs.compiler :as comp]
            [clojure.data.json :as json])
  (:import (java.io StringReader BufferedReader File)
           (java.util Base64)))

(defn closure-defines-json [{:keys [closure-defines] :as state}]
  (let [closure-defines
        (reduce-kv
          (fn [def key value]
            (let [key (if (symbol? key) (str (comp/munge key)) key)]
              (assoc def key value)))
          {}
          closure-defines)]

    (json/write-str closure-defines :escape-slashes false)))

(defn closure-defines [{:keys [asset-path cljs-runtime-path] :as state}]
  (str "var CLOSURE_NO_DEPS = true;\n"
       ;; goog.findBasePath_() requires a base.js which we dont have
       ;; this is usually only needed for unoptimized builds anyways
       "var CLOSURE_BASE_PATH = '" asset-path "/" cljs-runtime-path "/';\n"
       "var CLOSURE_DEFINES = " (closure-defines-json state) ";\n"))

(def goog-base-name "goog/base.js")

(defn closure-defines-and-base [{:keys [asset-path cljs-runtime-path] :as state}]
  (let [goog-rc (get-in state [:sources goog-base-name])
        goog-base @(:input goog-rc)]

    (when-not (seq goog-base)
      (throw (ex-info "no goog/base.js" {})))

    ;; FIXME: work arround for older cljs versions that used broked closure release, remove.
    (when (< (count goog-base) 500)
      (throw (ex-info "probably not the goog/base.js you were expecting"
               (get-in state [:sources goog-base-name]))))

    (str (closure-defines state)
         goog-base
         "\n")))

(defn- ns-list-string [coll]
  (->> coll
       (map #(str "'" (comp/munge %) "'"))
       (str/join ",")))

(defn directory? [^File x]
  (and (instance? File x)
       (or (not (.exists x))
           (.isDirectory x))))

(defn closure-goog-deps
  ([state]
   (closure-goog-deps state (-> state :sources keys)))
  ([state source-names]
   (->> source-names
        (remove #{"goog/base.js"})
        (map #(get-in state [:sources %]))
        (map (fn [{:keys [js-name require-order provides]}]
               (str "goog.addDependency(\"" js-name "\","
                    "[" (ns-list-string provides) "],"
                    "[" (->> require-order (remove '#{goog}) (ns-list-string)) "]);")))
        (str/join "\n"))))

;; FIXME: this could inline everything from a jar since they will never be live-reloaded
;; but it would need to create a proper source-map for the module file
;; since we need that for CLJS files
(defn inlineable? [{:keys [type from-jar provides requires] :as src}]
  ;; only inline things from jars
  (and from-jar
       ;; only js is inlineable since we want proper source maps for cljs
       (= :js type)
       ;; only inline goog for now
       (every? #(str/starts-with? (str %) "goog") requires)
       (every? #(str/starts-with? (str %) "goog") provides)
       ))

(defn line-count [text]
  (with-open [rdr (io/reader (StringReader. text))]
    (count (line-seq rdr))))


(defn generate-source-map-inline
  [state
   {:keys [name js-name input source-map source-map-json] :as src}
   prepend]
  (when (or source-map source-map-json)

    (let [source-map-json
          (or source-map-json
              (let [sm-opts
                    {;; :lines (line-count output)
                     :file js-name
                     :preamble-line-count (line-count prepend)
                     :sources-content [@input]}

                    source-map-cljs
                    (-> {js-name source-map}
                        (sm/encode* sm-opts)
                        (assoc "sources" [name])
                        ;; its nil which closure doesn't like
                        (dissoc "lineCount"))]

                (json/write-str source-map-cljs)))

          b64
          (-> (Base64/getEncoder)
              (.encodeToString (.getBytes source-map-json)))]

      (str "\n//# sourceMappingURL=data:application/json;charset=utf-8;base64," b64 "\n"))))

(defn generate-source-map
  [state
   {:keys [name js-name input source-map source-map-json] :as src}
   js-file
   prepend]
  (when (or source-map source-map-json)
    (let [sm-text
          (str "\n//# sourceMappingURL=" js-name ".map\n")

          src-map-file
          (io/file (str (.getAbsolutePath js-file) ".map"))

          source-map-json
          (or source-map-json
              (let [sm-opts
                    { ;; :lines (line-count output)
                     :file js-name
                     :preamble-line-count (line-count prepend)
                     :sources-content [@input]}

                    ;; yay or nay on using flat filenames for source maps?
                    ;; personally I don't like seeing only the filename without the path
                    source-map-v3
                    (-> {(util/flat-filename name) source-map}
                        (sm/encode* sm-opts)
                        (dissoc "lineCount") ;; its nil which closure doesn't like
                        ;; (assoc "sources" [name])
                        )]

                (json/write-str source-map-v3 :escape-slash false)
                ))]
      (spit src-map-file source-map-json)

      sm-text)))

(defn flush-sources-by-name
  ([{:keys [build-sources] :as state}]
   (flush-sources-by-name state build-sources))
  ([{:keys [output-dir cljs-runtime-path] :as state} source-names]
   (util/with-logged-time
     [state {:type :flush-sources
             :source-names source-names}]
     (doseq [src-name source-names
             :let [{:keys [js-name output last-modified] :as src}
                   (get-in state [:sources src-name])

                   js-file
                   (io/file output-dir cljs-runtime-path js-name)]

             ;; skip files we already have
             :when (or (not (.exists js-file))
                       (zero? last-modified)
                       ;; js is not compiled but maybe modified
                       (> (or (:compiled-at src) last-modified)
                          (.lastModified js-file)))]

       (io/make-parents js-file)

       (let [output
             (str output (generate-source-map state src js-file ""))]
         (spit js-file output)))

     state)))

(defn flush-foreign-bundles
  [{:keys [output-dir build-modules] :as state}]
  (doseq [{:keys [foreign-files] :as mod} build-modules
          {:keys [js-name provides output]} foreign-files]
    (let [target (io/file output-dir js-name)]
      (when-not (.exists target)

        (io/make-parents target)

        (util/log state {:type :flush-foreign
                         :provides provides
                         :js-name js-name
                         :js-size (count output)})

        (spit target output))))
  state)

(defn flush-optimized
  ;; FIXME: can't alias this due to cyclic dependency
  [{modules :shadow.cljs.closure/modules
    :keys [^File output-dir module-format]
    :as state}]

  (when-not (seq modules)
    (throw (ex-info "flush before optimize?" {})))

  (when-not output-dir
    (throw (ex-info "missing :output-dir" {})))

  (case module-format
    :goog
    (flush-foreign-bundles state)

    :js
    (let [env-file
          (io/file output-dir "cljs_env.js")]

      (io/make-parents env-file)
      (spit env-file
        (str "module.exports = {};\n"))))

  (util/with-logged-time
    [state {:type :flush-optimized
            :output-dir (.getAbsolutePath output-dir)}]

    (doseq [{:keys [dead prepend output append source-map-name source-map-json name js-name] :as mod} modules]
      (if dead
        (util/log state {:type :dead-module
                         :name name
                         :js-name js-name})

        (let [target
              (io/file output-dir js-name)

              source-map-name
              (str js-name ".map")

              ;; must not prepend anything else before output
              ;; will mess up source maps otherwise
              ;; append is fine
              final-output
              (str prepend
                   output
                   append
                   (when source-map-json
                     (str "\n//# sourceMappingURL=" source-map-name "\n")))]

          (io/make-parents target)

          (spit target final-output)

          (util/log state {:type :flush-module
                           :name name
                           :js-name js-name
                           :js-size (count final-output)})

          (when source-map-json
            (let [target (io/file output-dir source-map-name)]
              (io/make-parents target)
              (spit target source-map-json))))))

    ;; with-logged-time expects that we return the compiler-state
    state
    ))

(defn flush-unoptimized-module!
  [{:keys [dev-inline-js output-dir cljs-runtime-path asset-path unoptimizable] :as state}
   {:keys [default js-name prepend append sources web-worker] :as mod}]

  (let [inlineable-sources
        (if-not dev-inline-js
          []
          (->> sources
               (map #(get-in state [:sources %]))
               (filter inlineable?)
               (into [])))

        inlineable-set
        (into #{} (map :name) inlineable-sources)

        target
        (io/file output-dir js-name)

        inlined-js
        (->> inlineable-sources
             (map :output)
             (str/join "\n"))

        inlined-provides
        (->> inlineable-sources
             (mapcat :provides)
             (into #{}))

        ;; goog.writeScript_ (via goog.require) will set these
        ;; since we skip these any later goog.require (that is not under our control, ie REPL)
        ;; won't recognize them as loaded and load again
        closure-require-hack
        (->> inlineable-sources
             (map :js-name)
             (map (fn [js]
                    ;; not entirely sure why we are setting the full path and just the name
                    ;; goog seems to do that
                    (str "goog.dependencies_.written[\"" js "\"] = true;\n"
                         "goog.dependencies_.written[\"" asset-path "/" cljs-runtime-path "/" js "\"] = true;")
                    ))
             (str/join "\n"))

        requires
        (->> sources
             (remove inlineable-set)
             (mapcat #(get-in state [:sources % :provides]))
             (distinct)
             (remove inlined-provides)
             (remove '#{goog})
             (map (fn [ns]
                    (str "goog.require('" (comp/munge ns) "');")))
             (str/join "\n"))

        out
        (str inlined-js
             prepend
             closure-require-hack
             requires
             append)

        out
        (if (or default web-worker)
          ;; default mod needs closure related setup and goog.addDependency stuff
          (str unoptimizable
               (when web-worker
                 "\nvar CLOSURE_IMPORT_SCRIPT = function(src) { importScripts(src); };\n")
               (closure-defines-and-base state)
               (closure-goog-deps state (:build-sources state))
               "\n\n"
               out)
          ;; else
          out)]

    (spit target out)))

(defn flush-unoptimized!
  [{:keys [build-modules output-dir] :as state}]
  {:pre [(directory? output-dir)]}

  ;; FIXME: this always flushes
  ;; it could do partial flushes when nothing was actually compiled
  ;; a change in :closure-defines won't trigger a recompile
  ;; so just checking if nothing was compiled is not reliable enough
  ;; flushing really isn't that expensive so just do it always

  (when-not (seq build-modules)
    (throw (ex-info "flush before compile?" {})))

  (flush-sources-by-name state)

  (util/with-logged-time
    [state {:type :flush-unoptimized}]

    (doseq [mod build-modules]
      (flush-unoptimized-module! state mod))

    state
    ))

(defn flush-unoptimized
  [state]
  "util for ->"
  (flush-unoptimized! state)
  state)

(defn js-module-root [sym]
  (let [s (comp/munge (str sym))]
    (if-let [idx (str/index-of s ".")]
      (subs s 0 idx)
      s)))

(defn js-module-src-prepend [state {:keys [name js-name provides requires require-order] :as src} require?]
  (let [roots (into #{"goog"} (map js-module-root) requires)]

    (str (when require?
           "var $CLJS = require(\"./cljs_env\");\n")
         ;; the only actually global var goog sometimes uses that is not on goog.global
         ;; actually only: goog/promise/thenable.js goog/proto2/util.js?
         (when (str/starts-with? name "goog")
           "var COMPILED = false;\n")

         (when require?
           (->> require-order
                (remove #{'goog})
                (map (fn [sym]
                       (get-in state [:provide->source sym])))
                (distinct)
                (map #(get-in state [:sources %]))
                (remove util/foreign?)
                (map (fn [{:keys [js-name]}]
                       (str "require(\"./" (util/flat-filename js-name) "\");")))
                (str/join "\n")))
         "\n"
         ;; require roots will exist
         (->> roots
              (map (fn [root]
                     (str "var " root "=$CLJS." root ";")))
              (str/join "\n"))
         "\n"
         ;; provides may create new roots
         (->> provides
              (map js-module-root)
              (remove roots)
              (map (fn [root]
                     (str "var " root "=$CLJS." root " || ($CLJS." root " = {});")))
              (str/join "\n"))
         "\ngoog.dependencies_.written[" (pr-str js-name) "] = true;\n"
         "\n")))

(defn js-module-src-append [state {:keys [ns provides] :as src}]
  ;; export the shortest name always, some goog files have multiple provides
  (let [export
        (->> provides
             (map str)
             (sort)
             (map comp/munge)
             (first))]

    (str "\nmodule.exports = " export ";\n")))

(defn js-module-env
  [state {:keys [runtime] :or {runtime :node} :as config}]
  (str "var $CLJS = {};\n"
       "var CLJS_GLOBAL = process.browser ? window : global;\n"
       ;; closure accesses these defines via goog.global.CLOSURE_DEFINES
       "var CLOSURE_DEFINES = $CLJS.CLOSURE_DEFINES = " (closure-defines-json state) ";\n"
       "CLJS_GLOBAL.CLOSURE_NO_DEPS = true;\n"
       ;; so devtools can access it
       "CLJS_GLOBAL.$CLJS = $CLJS;\n"
       "var goog = $CLJS.goog = {};\n"
       ;; the global must be overriden in goog/base.js since it contains some
       ;; goog.define(...) which would otherwise be exported to "this"
       ;; but we need it on $CLJS
       (-> @(get-in state [:sources "goog/base.js" :input])
           (str/replace "goog.global = this;" "goog.global = $CLJS;"))

       ;; set global back to actual global so things like setTimeout work
       "\ngoog.global = CLJS_GLOBAL;"

       (slurp (io/resource "shadow/cljs/devtools/targets/npm_module_goog_overrides.js"))
       "\nmodule.exports = $CLJS;\n"
       ))

(defn flush-dev-js-modules
  [{::comp/keys [build-info] :keys [output-dir] :as state} mode config]

  (util/with-logged-time [state {:type :npm-flush :output-path (.getAbsolutePath output-dir)}]

    (let [env-file
          (io/file output-dir "cljs_env.js")

          env-content
          (js-module-env state config)

          env-modified?
          (or (not (.exists env-file))
              (not= env-content (slurp env-file)))]

      (when env-modified?
        (io/make-parents env-file)
        (spit env-file env-content))

      (doseq [src-name (:build-sources state)
              :let [src (get-in state [:sources src-name])]
              :when (not (util/foreign? src))]

        (let [{:keys [name js-name last-modified output]}
              src

              target
              (io/file output-dir js-name)]

          ;; flush everything if env was modified, otherwise only flush modified
          (when (or env-modified?
                    (contains? (:compiled build-info) name)
                    (not (.exists target))
                    (>= last-modified (.lastModified target)))

            (let [prepend
                  (js-module-src-prepend state src true)

                  content
                  (str prepend
                       output
                       (js-module-src-append state src)
                       (generate-source-map state src target prepend))]

              (spit target content)
              ))))))
  state)

