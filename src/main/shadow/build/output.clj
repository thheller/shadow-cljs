(ns shadow.build.output
  (:require
    [clojure.java.io :as io]
    [cljs.source-map :as sm]
    [clojure.set :as set]
    [clojure.string :as str]
    [cljs.compiler :as comp]
    [clojure.data.json :as json]
    [clojure.tools.logging :as log]
    [shadow.build.data :as data]
    [shadow.build.resource :as rc]
    [shadow.build.log :as build-log]
    [shadow.build.async :as async]
    [shadow.cljs.util :as util])
  (:import (java.io StringReader File ByteArrayOutputStream)
           (java.util Base64)
           (java.util.zip GZIPOutputStream)
           (shadow.build.closure SourceMapReport)))

(defn closure-defines-json [state]
  (let [closure-defines
        (reduce-kv
          (fn [def key value]
            (let [key (if (symbol? key) (str (comp/munge key)) key)]
              (assoc def key value)))
          {}
          (get-in state [:compiler-options :closure-defines] {}))]

    (json/write-str closure-defines :escape-slashes false)))

(defn closure-defines [{:keys [build-options] :as state}]
  (let [{:keys [asset-path cljs-runtime-path]} build-options]
    (str "var CLOSURE_NO_DEPS = true;\n"
         ;; goog.findBasePath_() requires a base.js which we dont have
         ;; this is usually only needed for unoptimized builds anyways
         "var CLOSURE_BASE_PATH = '" asset-path "/" cljs-runtime-path "/';\n"
         "var CLOSURE_DEFINES = " (closure-defines-json state) ";\n")))

(def goog-base-id
  ;; can't alias due to cyclic dependency, this sucks
  ;; goog/base.js is treated special in several cases
  [:shadow.build.classpath/resource "goog/base.js"])

(defn closure-defines-and-base [{:keys [asset-path cljs-runtime-path] :as state}]
  (let [goog-rc (get-in state [:sources goog-base-id])
        goog-base (get-in state [:output goog-base-id :js])]

    (when-not (seq goog-base)
      (throw (ex-info "no goog/base.js" {})))

    ;; FIXME: work arround for older cljs versions that used broked closure release, remove.
    (when (< (count goog-base) 500)
      (throw (ex-info "probably not the goog/base.js you were expecting"
               (get-in state [:sources goog-base-id]))))

    (str (closure-defines state)
         goog-base
         "\n")))

(defn ns-only [sym]
  {:pre [(qualified-symbol? sym)]}
  (symbol (namespace sym)))

(defn fn-call [sym]
  {:pre [(qualified-symbol? sym)]}
  (str "\n" (comp/munge sym) "();"))

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
  ([{:keys [dead-js-deps] :as state} source-ids]
   (->> source-ids
        (remove #{goog-base-id})
        (map #(get-in state [:sources %]))
        (map (fn [{:keys [output-name deps provides] :as rc}]
               (str "goog.addDependency(\"" output-name "\",["
                    (ns-list-string provides)
                    "],["
                    (->> (data/deps->syms state rc)
                         (remove '#{goog})
                         (remove dead-js-deps)
                         (ns-list-string))
                    "]);")))
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

(defn encode-source-map
  [{:keys [resource-name prepend output-name] :as src}
   {:keys [source source-map] :as output}]
  (let [sm-opts
        {;; :lines (line-count output)
         :file output-name
         :preamble-line-count (if-not (seq prepend)
                                0
                                (line-count prepend))
         :sources-content [source]}]

    (-> {output-name source-map}
        (sm/encode* sm-opts)
        (assoc "sources" [resource-name])
        ;; its nil which closure doesn't like
        (dissoc "lineCount"))))

(defn encode-source-map-json
  [src output]
  (-> (encode-source-map src output)
      (json/write-str)))

(defn generate-source-map-inline
  [state
   src
   {:keys [source-map source-map-json] :as output}
   prepend]
  (when (or source-map source-map-json)

    (let [source-map-json
          (or source-map-json
              (encode-source-map-json src output))

          b64
          (-> (Base64/getEncoder)
              (.encodeToString (.getBytes source-map-json)))]

      (str "\n//# sourceMappingURL=data:application/json;charset=utf-8;base64," b64 "\n"))))

(defn generate-source-map
  [state
   {:keys [resource-name output-name file input] :as src}
   {:keys [source source-map source-map-json] :as output}
   js-file
   prepend]
  {:pre [(rc/valid-resource? src)
         (map? output)]}
  (when (or source-map source-map-json)
    (let [sm-text
          (str "\n//# sourceMappingURL=" output-name ".map\n")

          src-map-file
          (io/file (str (.getAbsolutePath js-file) ".map"))

          use-fs-path?
          (true? (get-in state [:compiler-options :source-map-use-fs-paths]))

          source-url
          (if (and use-fs-path? file)
            (.getAbsolutePath file)
            resource-name)

          ;; FIXME: make this use encode-source-map from above
          source-map-json
          (or source-map-json
              (let [sm-opts
                    {;; :lines (line-count output)
                     :file output-name
                     :preamble-line-count (line-count prepend)
                     :sources-content [source]}

                    ;; yay or nay on using flat filenames for source maps?
                    ;; personally I don't like seeing only the filename without the path
                    source-map-v3
                    (-> {(util/flat-filename resource-name) source-map}
                        (sm/encode* sm-opts)
                        (dissoc "lineCount") ;; its nil which closure doesn't like
                        (assoc "sources" [source-url]))]

                (json/write-str source-map-v3 :escape-slash false)))]

      (io/make-parents src-map-file)
      (spit src-map-file source-map-json)

      sm-text)))

(defn flush-source [state src-id]
  (let [{:keys [resource-name output-name last-modified] :as src}
        (data/get-source-by-id state src-id)

        {:keys [js compiled-at] :as output}
        (data/get-output! state src)

        js-file
        (data/output-file state (get-in state [:build-options :cljs-runtime-path]) output-name)]

    ;; skip files we already have
    (when (or (not (.exists js-file))
              (zero? last-modified)
              ;; js is not compiled but maybe modified
              (> (or compiled-at last-modified)
                 (.lastModified js-file)))

      (io/make-parents js-file)

      (util/with-logged-time
        [state {:type :flush-source
                :resource-name resource-name}]

        (let [output (str js (generate-source-map state src output js-file ""))]
          (spit js-file output))))))

(defn flush-sources
  ([{:keys [build-sources] :as state}]
   (flush-sources state build-sources))
  ([state source-ids]
   (doseq [src-id source-ids]
     (async/queue-task state #(flush-source state src-id)))

   state))

(defmulti flush-optimized-module
  (fn [state mod]
    (get mod :output-type ::default)))

(defn finalize-module-output
  [state {:keys [goog-base prepend output append source-map-name source-map-json module-id output-name sources] :as mod}]
  (let [any-shadow-js?
        (->> (data/get-build-sources state)
             (some #(= :shadow-js (:type %))))

        shadow-js-outputs
        (->> sources
             (map #(data/get-source-by-id state %))
             (filter #(= :shadow-js (:type %)))
             (map #(data/get-output! state %))
             (into []))

        shadow-js-prepend
        (when (seq shadow-js-outputs)
          (let [provides
                (->> shadow-js-outputs
                     (map :js)
                     (str/join ";\n"))]
            (str provides
                 (when (seq provides)
                   ";\n"))))

        final-output
        (str (when (and any-shadow-js? goog-base)
               "var shadow$provide = {};\n")
             prepend
             shadow-js-prepend
             output
             append)]

    {:output final-output
     ;; for source maps
     :shadow-js-outputs shadow-js-outputs
     :prepend-offset
     (-> 0
         (cond->
           (seq prepend)
           (+ (line-count prepend))
           (and goog-base)
           (inc) ;; var shadow$provide ...
           ))}))

(defmethod flush-optimized-module ::default
  [state {:keys [dead source-map-json module-id output-name] :as mod}]
  (if dead
    (util/log state {:type :dead-module
                     :module-id module-id
                     :output-name output-name})

    (let [{:keys [output prepend-offset shadow-js-outputs]}
          (finalize-module-output state mod)

          target
          (data/output-file state output-name)

          source-map-name
          (str output-name ".map")

          final-output
          (str output
               (when source-map-json
                 (str "\n//# sourceMappingURL=" source-map-name "\n")))]

      (io/make-parents target)

      (spit target final-output)

      (util/log state {:type :flush-module
                       :module-id module-id
                       :output-name output-name
                       :js-size (count final-output)})

      (when source-map-json
        (let [sm-index
              (-> {:version 3
                   :file output-name
                   :offset prepend-offset
                   :sections []}
                  (util/reduce->
                    (fn [{:keys [offset] :as sm-index}
                         {:keys [js source-map-json] :as src}]

                      (let [sm
                            (json/read-str (or source-map-json "{}"))

                            lines
                            (line-count js)]
                        (-> sm-index
                            (update :offset + lines)
                            (update :sections conj {:offset {:line offset :column 0}
                                                    :map sm}))))

                    shadow-js-outputs))

              sm
              (json/read-str source-map-json)

              sm-index
              (-> sm-index
                  (update :sections conj {:offset {:line (:offset sm-index) :column 0} :map sm})
                  (dissoc :offset))

              target
              (data/output-file state source-map-name)]
          (spit target
            (json/write-str sm-index)))))))

(defn flush-optimized
  ;; FIXME: can't alias this due to cyclic dependency
  [{modules :shadow.build.closure/modules
    :keys [build-options]
    :as state}]

  (when-not (seq modules)
    (throw (ex-info "flush before optimize?" {})))

  (when (= :js (get-in state [:build-options :module-format]))
    (let [env-file
          (data/output-file state "cljs_env.js")]

      (io/make-parents env-file)
      (spit env-file
        (str "module.exports = {};\n"))))

  (util/with-logged-time
    [state {:type :flush-optimized}]

    (doseq [mod modules]
      (flush-optimized-module state mod))

    ;; with-logged-time expects that we return the compiler-state
    state
    ))

(defn js-module-root [sym]
  (let [s (comp/munge (str sym))]
    (if-let [idx (str/index-of s ".")]
      (subs s 0 idx)
      s)))

(defn js-module-src-prepend [state {:keys [resource-id resource-name output-name provides requires deps] :as src} require?]
  (let [dep-syms
        (data/deps->syms state src)

        roots
        (into #{"goog"} (map js-module-root) dep-syms)]

    (str (when require?
           (str "var $CLJS = require(\"./cljs_env\");\n"
                "var $jscomp = $CLJS.$jscomp;\n"))
         ;; the only actually global var goog sometimes uses that is not on goog.global
         ;; actually only: goog/promise/thenable.js goog/proto2/util.js?
         (when (str/starts-with? resource-name "goog")
           "var COMPILED = false;\n")

         (when require?
           ;; emit requires to actual files to ensure that they were loaded properly
           ;; can't ensure that the files were loaded before this as goog.require would
           (->> dep-syms
                (remove #{'goog})
                (map #(data/get-source-id-by-provide state %))
                (distinct)
                (map #(data/get-source-by-id state %))
                (remove util/foreign?)
                (map (fn [{:keys [output-name] :as x}]
                       (str "require(\"./" output-name "\");")))
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
         "\n$CLJS.SHADOW_ENV.setLoaded(" (pr-str output-name) ");\n"
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
  [{:keys [polyfill-js] :as state} {:keys [runtime] :or {runtime :node} :as config}]
  (str "var $CLJS = {};\n"
       "var CLJS_GLOBAL = process.browser ? (typeof(window) != 'undefined' ? window : self) : global;\n"
       ;; closure accesses these defines via goog.global.CLOSURE_DEFINES
       "var CLOSURE_DEFINES = $CLJS.CLOSURE_DEFINES = " (closure-defines-json state) ";\n"
       "CLJS_GLOBAL.CLOSURE_NO_DEPS = true;\n"
       ;; so devtools can access it
       "CLJS_GLOBAL.$CLJS = $CLJS;\n"
       "var goog = $CLJS.goog = {};\n"
       ;; the global must be overriden in goog/base.js since it contains some
       ;; goog.define(...) which would otherwise be exported to "this"
       ;; but we need it on $CLJS
       (-> (data/get-output! state {:resource-id goog-base-id})
           (get :js)
           (str/replace "goog.global = this;" "goog.global = $CLJS;"))

       (if (seq polyfill-js)
         (str "\n" polyfill-js
              "\n$CLJS.$jscomp = $jscomp;")
         (str "\n$CLJS.$jscomp = {};"))

       ;; set global back to actual global so things like setTimeout work
       "\ngoog.global = CLJS_GLOBAL;"

       (slurp (io/resource "shadow/boot/static.js"))
       (slurp (io/resource "shadow/build/targets/npm_module_goog_overrides.js"))
       "\nmodule.exports = $CLJS;\n"
       ))

(defn flush-dev-js-modules
  [{::comp/keys [build-info] :as state} mode config]

  (util/with-logged-time [state {:type :npm-flush
                                 :output-path (.getAbsolutePath (get-in state [:build-options :output-dir]))}]

    (let [env-file
          (data/output-file state "cljs_env.js")

          env-content
          (js-module-env state config)

          env-modified?
          (or (not (.exists env-file))
              (not= env-content (slurp env-file)))]

      (when env-modified?
        (io/make-parents env-file)
        (spit env-file env-content))

      (doseq [src-id (:build-sources state)
              :when (not= src-id goog-base-id)
              :let [src (get-in state [:sources src-id])]
              :when (not (util/foreign? src))]

        (let [{:keys [resource-name output-name last-modified]}
              src

              {:keys [js] :as output}
              (data/get-output! state src)

              target
              (data/output-file state output-name)]

          ;; flush everything if env was modified, otherwise only flush modified
          (when (or env-modified?
                    (contains? (:compiled build-info) resource-name)
                    (not (.exists target))
                    (>= last-modified (.lastModified target)))

            (let [prepend
                  (js-module-src-prepend state src true)

                  content
                  (str prepend
                       js
                       (js-module-src-append state src)
                       (generate-source-map state src output target prepend))]

              (spit target content)
              ))))))
  state)

(defmethod build-log/event->str ::generate-bundle-info
  [event]
  "Generate bundle-info.edn")

(defn generate-bundle-info
  [{:shadow.build.closure/keys [optimized-bytes modules] :keys [build-sources] :as state}]
  (util/with-logged-time [state {:type ::generate-bundle-info}]
    (let [modules-info
          (->> modules
               (map (fn [{:keys [module-id sources depends-on output-name goog-base prepend output append] :as mod}]
                      (let [out-file
                            (data/output-file state output-name)

                            out-map-file
                            (data/output-file state (str output-name ".map"))

                            byte-map
                            (SourceMapReport/getByteMap out-file out-map-file)

                            bytes-out
                            (ByteArrayOutputStream.)

                            zip-out
                            (GZIPOutputStream. bytes-out)]

                        (io/copy (slurp out-file) zip-out)
                        (.flush zip-out)
                        (.close zip-out)

                        {:module-id module-id
                         :sources sources
                         :depends-on depends-on
                         :source-bytes byte-map
                         :js-size (.length out-file)
                         :gzip-size (.size bytes-out)}
                        )
                      ))
               (into []))

          src->mod
          (->> (for [{:keys [module-id sources] :as mod} modules
                     src sources]
                 [src module-id])
               (into {}))

          sources-info
          (->> build-sources
               (map (fn [src-id]
                      (let [{:keys [resource-name pom-info npm-info package-name output-name type provides] :as src}
                            (data/get-source-by-id state src-id)

                            {:keys [js source] :as output}
                            (data/get-output! state src)]

                        (-> {:resource-id src-id
                             :resource-name resource-name
                             :module-id (get src->mod src-id)
                             :type type
                             :output-name output-name
                             :provides provides
                             :requires (into #{} (data/deps->syms state src))
                             :js-size (count js)}
                            (cond->
                              (seq package-name)
                              (assoc :package-name package-name)

                              pom-info
                              (assoc :pom-info pom-info)

                              npm-info
                              (assoc :npm-info npm-info)

                              (string? source)
                              (assoc :source-size (count source))
                              )))))
               (into []))]

      {:build-modules modules-info
       :build-sources sources-info})))