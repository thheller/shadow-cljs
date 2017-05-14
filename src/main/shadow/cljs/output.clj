(ns shadow.cljs.output
  (:require [clojure.java.io :as io]
            [cljs.source-map :as sm]
            [shadow.cljs.util :as util]
            [clojure.string :as str]
            [cljs.compiler :as comp]
            [clojure.data.json :as json])
  (:import (java.io StringReader BufferedReader File)))

(defn closure-defines-json [{:keys [closure-defines] :as state}]
  (let [closure-defines
        (reduce-kv
          (fn [def key value]
            (let [key (if (symbol? key) (str (comp/munge key)) key)]
              (assoc def key value)))
          {}
          closure-defines)]

    (json/write-str closure-defines :escape-slashes false)))

(defn closure-defines [{:keys [public-path cljs-runtime-path] :as state}]
  (str "var CLOSURE_NO_DEPS = true;\n"
       ;; goog.findBasePath_() requires a base.js which we dont have
       ;; this is usually only needed for unoptimized builds anyways
       "var CLOSURE_BASE_PATH = '" public-path "/" cljs-runtime-path "/';\n"
       "var CLOSURE_DEFINES = " (closure-defines-json state) ";\n"))

(def goog-base-name "goog/base.js")

(defn closure-defines-and-base [{:keys [public-path cljs-runtime-path] :as state}]
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
;; compact-mode has a bunch of work for index source-maps, should be an easy port
(defn inlineable? [{:keys [type from-jar provides requires] :as src}]
  ;; only inline things from jars
  (and from-jar
       ;; only js is inlineable since we want proper source maps for cljs
       (= :js type)
       ;; only inline goog for now
       (every? #(str/starts-with? (str %) "goog") requires)
       (every? #(str/starts-with? (str %) "goog") provides)
       ))

(defn line-count [s]
  (-> (StringReader. s)
      (BufferedReader.)
      (line-seq)
      (count)))

(defn flush-sources-by-name
  ([state]
   (flush-sources-by-name state (mapcat :sources (:build-modules state))))
  ([{:keys [public-dir cljs-runtime-path] :as state} source-names]
   (util/with-logged-time
     [state {:type :flush-sources
             :source-names source-names}]
     (doseq [src-name source-names
             :let [{:keys [js-name name input output last-modified source-map source-map-json] :as src}
                   (get-in state [:sources src-name])

                   js-file
                   (io/file public-dir cljs-runtime-path js-name)

                   src-file
                   (io/file public-dir cljs-runtime-path name)

                   src-map?
                   (and (:source-map state) (or source-map source-map-json))

                   src-map-file
                   (io/file public-dir cljs-runtime-path (str js-name ".map"))]

             ;; skip files we already have
             :when (or (not (.exists js-file))
                       (zero? last-modified)
                       ;; js is not compiled but maybe modified
                       (> (or (:compiled-at src) last-modified)
                          (.lastModified js-file))
                       (and src-map? (or (not (.exists src-file))
                                         (not (.exists src-map-file)))))]

       (io/make-parents js-file)

       ;; must not modify output in any way, will mess up source maps otherwise
       (spit js-file output)

       (when src-map?
         ;; spit original source, needed for source maps
         (spit src-file @input)

         (let [source-map-json
               (or source-map-json
                   (sm/encode
                     {name source-map}
                     ;; very important that :lines is accurate for closure source maps, otherwise unused
                     {:lines (line-count output)
                      :file js-name
                      :preamble-line-count 0}))]

           (spit src-map-file source-map-json))))

     state)))

(defn flush-foreign-bundles
  [{:keys [public-dir build-modules] :as state}]
  (doseq [{:keys [foreign-files] :as mod} build-modules
          {:keys [js-name provides output]} foreign-files]
    (let [target (io/file public-dir js-name)]
      (when-not (.exists target)

        (io/make-parents target)

        (util/log state {:type :flush-foreign
                         :provides provides
                         :js-name js-name
                         :js-size (count output)})

        (spit target output))))
  state)

(defn flush-modules-to-disk
  [{modules :optimized
    :keys [^File public-dir cljs-runtime-path]
    :as state}]

  (flush-foreign-bundles state)

  (util/with-logged-time
    [state {:type :flush-optimized}]

    (when-not (seq modules)
      (throw (ex-info "flush before optimize?" {})))

    (when-not public-dir
      (throw (ex-info "missing :public-dir" {})))

    (doseq [{:keys [output source-map-name source-map-json name js-name] :as mod} modules]
      (let [target (io/file public-dir js-name)]

        (io/make-parents target)

        (spit target output)

        (util/log state {:type :flush-module
                         :name name
                         :js-name js-name
                         :js-size (count output)})

        (when source-map-name
          (let [target (io/file public-dir cljs-runtime-path source-map-name)]
            (io/make-parents target)
            (spit target source-map-json)))))

    ;; with-logged-time expects that we return the compiler-state
    state
    ))

(defn flush-unoptimized-module!
  [{:keys [dev-inline-js public-dir public-path unoptimizable] :as state}
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
        (io/file public-dir js-name)

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
                         "goog.dependencies_.written[\"" public-path "/" js "\"] = true;")
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
  [{:keys [build-modules public-dir] :as state}]
  {:pre [(directory? public-dir)]}

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

(defn line-count [text]
  (with-open [rdr (io/reader (StringReader. text))]
    (count (line-seq rdr))))

(defn create-index-map
  [{:keys [public-dir cljs-runtime-path] :as state}
   out-file
   init-offset
   {:keys [sources js-name] :as mod}]
  (let [index-map
        (reduce
          (fn [src-map src-name]
            (let [{:keys [type output js-name] :as rc} (get-in state [:sources src-name])
                  source-map-file (io/file public-dir cljs-runtime-path (str js-name ".map"))
                  lc (line-count output)
                  start-line (:current-offset src-map)

                  ;; extra 2 lines per file
                  ;; // SOURCE comment
                  ;; goog.dependencies_.written[src] = true;
                  src-map (update src-map :current-offset + lc 2)]

              (if (and (= :cljs type)
                       (.exists source-map-file))
                (update src-map :sections conj {:offset {:line (+ start-line 3) :column 0}
                                                ;; :url (str js-name ".map")
                                                ;; chrome doesn't support :url
                                                ;; see https://code.google.com/p/chromium/issues/detail?id=552455
                                                ;; FIXME: inlining the source-map is expensive due to excessive parsing
                                                ;; could try to insert MARKER instead and str/replace
                                                ;; 300ms is acceptable for now, but might not be on bigger projects
                                                ;; flushing the unoptmized version should not exceed 100ms
                                                :map (let [sm (json/read-str (slurp source-map-file))]
                                                       ;; must set sources and file to complete relative paths
                                                       ;; as the source map only contains local references without path
                                                       (assoc sm
                                                              "sources" [src-name]
                                                              "file" js-name))
                                                })
                ;; only have source-maps for cljs
                src-map)
              ))
          {:current-offset init-offset
           :version 3
           :file (str "../" js-name)
           :sections []}
          sources)

        index-map (dissoc index-map :current-offset)]

    ;; (pprint index-map)
    (spit out-file (json/write-str index-map))
    ))

(defn flush-unoptimized-compact
  [{:keys [build-modules public-dir unoptimizable cljs-runtime-path] :as state}]
  {:pre [(directory? public-dir)]}

  (when-not (seq build-modules)
    (throw (ex-info "flush before compile?" {})))

  (flush-sources-by-name state)

  (util/with-logged-time
    [state {:type :flush-unoptimized
            :compact true}]

    ;; flush fake modules
    (doseq [{:keys [default js-name name prepend append sources web-worker] :as mod} build-modules]
      (let [target (io/file public-dir js-name)
            append-to-target
            (fn [text]
              (spit target text :append true))]

        (spit target prepend)
        (when (or default web-worker)
          (append-to-target
            (str unoptimizable
                 (if web-worker
                   "\nvar SHADOW_IMPORT = function(src) { importScripts(src); };\n"
                   ;; FIXME: should probably throw an error because we NEVER want to import anything this way
                   "\nvar SHADOW_IMPORT = function(src, opt_sourceText) { console.log(\"BROKEN IMPORT\", src); };\n"
                   )
                 (closure-defines-and-base state)
                 (closure-goog-deps state (:build-sources state))
                 "\n\n"
                 )))

        ;; at least line-count must be captured here
        ;; since it is the initial offset before we actually have a source map
        (create-index-map
          state
          (io/file public-dir cljs-runtime-path (str (clojure.core/name name) "-index.js.map"))
          (line-count (slurp target))
          mod)

        (doseq [src-name sources
                :let [{:keys [output name js-name] :as rc} (get-in state [:sources src-name])]]
          (append-to-target (str "// SOURCE=" name "\n"))
          ;; pretend we actually loaded a separate file, live-reload needs this
          (append-to-target (str "goog.dependencies_.written[" (pr-str js-name) "] = true;\n"))
          (append-to-target (str (str/trim (str/replace output "//# sourceMappingURL=" "// ")) "\n")))

        (append-to-target (str "//# sourceMappingURL=" cljs-runtime-path "/" (clojure.core/name name) "-index.js.map\n"))
        )))

  ;; return unmodified state
  state)
