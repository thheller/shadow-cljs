(ns shadow.build.npm
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [cljs.compiler :as cljs-comp]
            [shadow.jvm-log :as log]
            [shadow.build.resource :as rc]
            [shadow.build.log :as cljs-log]
            [shadow.cljs.util :as util :refer (reduce-> reduce-kv->)]
            [shadow.build.data :as data]
            [clojure.edn :as edn]
            [shadow.debug :as dbg])
  (:import (java.io File)
           (com.google.javascript.jscomp SourceFile CompilerOptions CompilerOptions$LanguageMode)
           (com.google.javascript.jscomp.deps ModuleNames)
           (shadow.build.closure JsInspector)
           [java.nio.file Path]))

(set! *warn-on-reflection* true)

(defn condition-only-map? [exports]
  (reduce-kv
    (fn [x key val]
      (if (str/starts-with? key ".")
        (reduced false)
        x))
    true
    exports))

(defn path-only-map? [exports]
  (reduce-kv
    (fn [x key val]
      (if-not (str/starts-with? key ".")
        (reduced false)
        x))
    true
    exports))

(comment
  (condition-only-map? {"a" 1 "b" 2})
  (condition-only-map? {"a" 1 "./b" 2})
  (path-only-map? {"./a" 1 "./b" 2})
  (path-only-map? {"./a" 1 "b" 2}))

;; https://github.com/jkrems/proposal-pkg-exports
;; https://webpack.js.org/guides/package-exports/
;; https://nodejs.org/api/packages.html#packages_exports

(defn decipher-path-exports [exports]
  (reduce-kv
    (fn [m key val]
      (cond
        (str/includes? key "*")
        (update m :wildcard-match conj [key val])

        (str/ends-with? key "/")
        (update m :prefix-match conj [key val])

        :else
        (update m :exact-match conj [key val])))
    {:exact-match []
     :prefix-match []
     :wildcard-match []}
    exports))

(defn decipher-exports [exports]
  (cond
    (string? exports)
    [:try-one exports]

    (vector? exports)
    [:try-many exports]

    (not (map? exports))
    (throw (ex-info "invalid package.json exports value" {:exports exports}))

    (condition-only-map? exports)
    [:condition-match exports]

    (path-only-map? exports)
    [:path-match (decipher-path-exports exports)]

    :else
    (throw (ex-info "invalid package.json exports value" {:exports exports}))
    ))

(defn match-exports [exports path conditions]
  (cond
    (string? exports)
    ::TBD

    (vector? exports)
    ::TBD

    (not (map? exports))
    (throw (ex-info "invalid package.json exports value" {:exports exports}))

    (condition-only-map? exports)
    ::TBD

    (path-only-map? exports)
    (if-some [exact-match (get exports path)]
      [:exact-match exact-match]
      )


    :else
    (throw (ex-info "invalid package.json exports value" {:exports exports}))

    ))

;; used in every resource :cache-key to make sure it invalidates when shadow-cljs updates
(def NPM-CACHE-KEY
  (data/sha1-url (io/resource "shadow/build/npm.clj")))

(def CLOSURE-CACHE-KEY
  (data/sha1-url (io/resource "shadow/build/closure.clj")))

(defn service? [x]
  (and (map? x) (::service x)))

(defn maybe-kw-as-string [x]
  (cond
    (string? x)
    x

    (keyword? x)
    (name x)

    (symbol? x)
    (name x)

    :else
    nil))

(defn collect-npm-deps-from-classpath []
  (->> (-> (Thread/currentThread)
           (.getContextClassLoader)
           (.getResources "deps.cljs")
           (enumeration-seq))
       (map slurp)
       (map edn/read-string)
       (mapcat #(-> % (get :npm-deps) (keys)))
       (map maybe-kw-as-string)
       (remove nil?)
       (set)
       ))

(comment
  (collect-npm-deps-from-classpath))

(defn absolute-file
  ".getCanonicalFile resolves links but we just want to replace . and .."
  ^File [^File x]
  (-> x
      (.toPath)
      (.toAbsolutePath)
      (.normalize)
      (.toFile)))

(defn read-package-json
  "this caches the contents package.json files since we may access them quite often when resolving deps"
  [{:keys [index-ref] :as state} ^File file]
  (let [last-modified (.lastModified file)

        cached
        (get-in @index-ref [:package-json-cache file])]

    (if (and cached (= last-modified (:last-modified cached)))
      (:content cached)
      (let [{:strs [dependencies name version browser] :as package-json}
            (-> (slurp file)
                (json/read-str))

            package-dir
            (.getAbsoluteFile (.getParentFile file))

            content
            (-> {:package-name name
                 ;; :package-name is no longer a unique identifier with nested installs
                 ;; need a unique identifier for build reports since they get otherwise
                 ;; grouped together incorrectly. building it here since this has the most info
                 :package-id (str (.getAbsolutePath package-dir) "@" version)
                 :package-dir package-dir
                 :package-json package-json
                 :version version
                 :dependencies (into #{} (keys dependencies))}
                (cond->
                  (string? browser)
                  (assoc :browser browser)

                  ;; browser can point to a string and to an object
                  ;; since object has an entirely different meaning
                  ;; don't use it as a main
                  (map? browser)
                  (-> (assoc :browser-overrides browser)
                      (update :package-json dissoc "browser"))))]

        (swap! index-ref assoc-in [:package-json-cache file] {:content content
                                                              :last-modified last-modified})
        content
        ))))

(defn find-package-json [^File file]
  (loop [root (if (.isDirectory file)
                (.getParentFile file)
                file)]
    (when root
      (let [package-json (io/file root "package.json")]
        (if (and (.exists package-json)
                 (.isFile package-json))
          package-json
          (recur (.getParentFile root))
          )))))

(defn find-package-for-file [npm file]
  (when-let [package-json-file (find-package-json file)]
    (read-package-json npm package-json-file)))

(defn test-file ^File [^File dir name]
  (when name
    (let [file
          (-> (io/file dir name)
              (absolute-file))]
      (when (.exists file)
        file))))

(defn test-file-exts ^File [npm ^File dir name]
  (let [extensions (get-in npm [:js-options :extensions])]
    (reduce
      (fn [_ ext]
        (when-let [path (test-file dir (str name ext))]
          (when (.isFile path)
            (reduced path))))
      nil
      extensions)))

(defn find-package** [npm modules-dir package-name]
  (let [package-dir (io/file modules-dir package-name)]
    (when (.exists package-dir)
      (let [package-json-file (io/file package-dir "package.json")]
        (when (.exists package-json-file)
          (read-package-json npm package-json-file))))))

(defn get-package-info [npm ^File package-json-file]
  (when (.exists package-json-file)
    (read-package-json npm package-json-file)))

(defn find-package* [{:keys [js-package-dirs] :as npm} package-name]
  ;; check all configured :js-package-dirs but only those
  ;; never automatically go up/down like node resolve does
  (reduce
    (fn [_ modules-dir]
      (when-let [pkg (find-package** npm modules-dir package-name)]
        (reduced (assoc pkg :js-package-dir modules-dir))))
    nil
    js-package-dirs))

(defn find-package [{:keys [index-ref] :as npm} package-name]
  {:pre [(string? package-name)
         (seq package-name)]}
  (or (get-in @index-ref [:packages package-name])
      (let [pkg-info (find-package* npm package-name)]
        (swap! index-ref assoc-in [:packages package-name] pkg-info)
        pkg-info)))

(defn with-npm-info [npm package rc]
  (assoc rc
    ::package package
    ;; FIXME: rewrite all uses of this so it looks at ::package instead
    :package-name (:package-name package)))

(defn is-npm-dep? [{:keys [npm-deps]} ^String require]
  (contains? npm-deps require))

;; https://github.com/webpack/node-libs-browser/blob/master/index.js
;; using this package so have the same dependencies that webpack would use
(def node-libs-browser
  {"child_process" false
   "xmlhttprequest" false
   "cluster" false
   "console" "console-browserify"
   "constants" "constants-browserify"
   "crypto" "crypto-browserify"
   "dgram" false
   "dns" false
   "domain" "domain-browser"
   "fs" false
   "http" "stream-http"
   "https" "https-browserify"
   "module" false
   "net" false
   "os" "os-browserify/browser.js"
   "path" "path-browserify"
   "process" "process/browser.js"
   "querystring" "querystring-es3"
   "readline" false
   "repl" false
   "stream" "stream-browserify"
   "_stream_duplex" "readable-stream/duplex.js"
   "_stream_passthrough" "readable-stream/passthrough.js"
   "_stream_readable" "readable-stream/readable.js"
   "_stream_transform" "readable-stream/transform.js"
   "_stream_writable" "readable-stream/writable.js"
   "sys" "util/util.js"
   "timers" "timers-browserify"
   "tls" false
   "tty" "tty-browserify"
   "util" "util/util.js"
   ;; just has a bunch of isThing functions that are in util
   ;; but also tries to use a Buffer global, which doesn't exist
   "core-util-is" "util/util.js"
   "vm" "vm-browserify"
   "zlib" "browserify-zlib"})

(def empty-rc
  (let [ns 'shadow$empty]
    {:resource-id [::empty "shadow$empty.js"]
     :resource-name "shadow$empty.js"
     :output-name "shadow$empty.js"
     :type :js
     :cache-key [] ;; change this if this ever changes
     :last-modified 0
     :ns ns
     :provides #{ns}
     :requires #{}
     :deps '[shadow.js]
     :source ""}))

(defn maybe-convert-goog [dep]
  (if-not (str/starts-with? dep "goog:")
    dep
    (symbol (subs dep 5))))

(def asset-exts
  #{"css"
    "scss"
    "sass"
    "less"
    "png"
    "gif"
    "jpg"
    "jpeg"
    "svg"})

(defn asset-require? [require]
  (when-let [dot (str/last-index-of require ".")]
    (let [ext (str/lower-case (subs require (inc dot)))]
      (contains? asset-exts ext)
      )))

(defn disambiguate-module-name
  "the variable names chosen by closure are not unique enough
   object.assign creates the same variable as object-assign
   so this makes the name more unique to avoid the clash"
  [name]
  (let [slash-idx (str/index-of name "/")]
    (if-not slash-idx
      name
      (let [module-name (subs name 0 slash-idx)]
        (str (str/replace module-name #"\." "_DOT_")
             (subs name slash-idx))))))

(comment
  (disambiguate-module-name "object.assign/index.js")
  (disambiguate-module-name "object-assign/index.js")
  )

(defn resource-name-for-file [{:keys [^File project-dir js-package-dirs] :as npm} ^File file]
  (let [^Path abs-path
        (.toPath project-dir)

        ^Path file-path
        (.toPath file)

        ^Path node-modules-path
        (->> js-package-dirs
             (map (fn [^File modules-dir] (.toPath modules-dir)))
             (filter (fn [^Path path]
                       (.startsWith file-path path)))
             (sort-by (fn [^Path path] (.getNameCount path)))
             (reverse) ;; FIXME: pick longest match might not always be the best choice?
             (first))

        npm-file?
        (some? node-modules-path)

        _ (when-not (or npm-file? (.startsWith file-path abs-path))
            (throw (ex-info (format "files outside the project are not allowed: %s" file-path)
                     {:file file})))]

    (if npm-file?
      (->> (.relativize node-modules-path file-path)
           (str)
           (rc/normalize-name)
           (disambiguate-module-name)
           (str "node_modules/"))
      (->> (.relativize abs-path file-path)
           (str)
           (rc/normalize-name)))))

(defn get-file-info*
  "extract some basic information from a given file, does not resolve dependencies"
  [{:keys [compiler] :as npm} ^File file]
  {:pre [(service? npm)
         (util/is-file-instance? file)
         (.isAbsolute file)]}

  ;; normalize node_modules files since they may not be at the root of the project
  (let [resource-name
        (resource-name-for-file npm file)

        ns (-> (ModuleNames/fileToModuleName resource-name)
               ;; (cljs-comp/munge) ;; FIXME: the above already does basically the same, does it cover everything?
               ;; WTF node ppl ... node_modules/es5-ext/array/#/index.js
               (str/replace #"#" "_HASH_")
               (symbol))

        last-modified
        (.lastModified file)

        source
        (slurp file)

        cache-key
        [NPM-CACHE-KEY CLOSURE-CACHE-KEY (data/sha1-string source)]]

    ;; require("../package.json").version is a thing
    ;; no need to parse it since it can't have any require/import/export
    (-> (if (str/ends-with? (.getName file) ".json")
          {:resource-id [::resource resource-name]
           :resource-name resource-name
           :output-name (str ns ".js")
           :json true
           :type :js
           :file file
           :last-modified last-modified
           :cache-key cache-key
           :ns ns
           :provides #{ns}
           :requires #{}
           :source source
           :js-deps []}

          ;; FIXME: check if a .babelrc applies and then run source through babel first
          ;; that should take care of .jsx and others if I actually want to support that?
          ;; all requires are collected into
          ;; :js-requires ["foo" "bar/thing" "./baz]
          ;; all imports are collected into
          ;; :js-imports ["react"]
          (let [{:keys [js-requires js-imports js-errors js-warnings js-invalid-requires js-language] :as info}
                (try
                  (JsInspector/getFileInfoMap
                    compiler
                    ;; SourceFile/fromFile seems to leak file descriptors
                    (SourceFile/fromCode (.getAbsolutePath file) source))
                  (catch Exception e
                    (throw (ex-info (format "errors in file: %s" (.getAbsolutePath file))
                             {:tag ::file-info-errors
                              :info {:js-errors [{:line 1 :column 1 :message "The file could not be parsed as JavaScript."}]}
                              :file file}
                             e))))

                js-deps
                (->> (concat js-requires js-imports)
                     (distinct)
                     (map maybe-convert-goog)
                     (into []))

                js-deps
                (cond-> js-deps
                  (:uses-global-buffer info)
                  (conj "buffer")
                  (:uses-global-process info)
                  (conj "process"))]

            (when (seq js-errors)
              (throw (ex-info (format "errors in file: %s" (.getAbsolutePath file))
                       {:tag ::file-info-errors
                        :info info
                        :file file})))

            ;; moment.js has require('./locale/' + name); inside a function
            ;; it shouldn't otherwise hurt though
            (when (seq js-invalid-requires)
              (log/info ::js-invalid-requires {:resource-name resource-name
                                               :requires js-invalid-requires}))

            (assoc info
              :resource-id [::resource resource-name]
              :resource-name resource-name
              ;; work around file names ending up too long on some linux systems for certain npm deps
              ;; FileNotFoundException: .shadow-cljs/builds/foo/dev/shadow-js/module$node_modules$$emotion$react$isolated_hoist_non_react_statics_do_not_use_this_in_your_code$dist$emotion_react_isolated_hoist_non_react_statics_do_not_use_this_in_your_code_browser_cjs.js (File name too long)
              :output-name
              (if (> (count resource-name) 127)
                (str "module$too_long_" (util/md5hex resource-name) ".js")
                (str ns ".js"))
              :type :js
              :file file
              :last-modified last-modified
              :cache-key cache-key
              :ns ns
              :provides #{ns}
              :requires #{}
              :source source
              :js-language js-language
              :js-deps js-deps
              :deps js-deps))))))

(defn get-file-info [{:keys [index-ref] :as npm} ^File file]
  {:pre [(service? npm)]}
  (or (get-in @index-ref [:files file])
      (let [file-info (get-file-info* npm file)]
        (swap! index-ref assoc-in [:files file] file-info)
        file-info
        )))

(defn find-package-for-require* [npm {::keys [package] :as require-from} require]
  (if (or (not require-from) (not (:allow-nested-packages (:js-options npm))))
    (find-package npm require)
    ;; node/webpack resolve rules look for nested node_modules packages
    ;; try to find those here
    (let [{:keys [js-package-dir package-dir]} package]
      (loop [^File ref-dir package-dir]
        (cond
          ;; don't go further than js-package-dir, just look up package regularly at that point
          ;; doing so by requiring without require-from, which will end up check all js-package-dirs again
          (= ref-dir js-package-dir)
          (find-package npm require)

          ;; no need to try node_modules/node_modules
          (= "node_modules" (.getName ref-dir))
          (recur (.getParentFile ref-dir))

          ;; otherwise check for nested install, which might have multiple levels, need to check all
          ;; node_modules/a/node_modules/b/node_modules/c
          ;; node_modules/a/node_modules/c
          ;; node_modules/c is then checked by find-package above again
          :check
          (let [nested-package-file (io/file ref-dir "node_modules" require "package.json")]
            (if (.exists nested-package-file)
              (when-some [pkg (read-package-json npm nested-package-file)]
                ;; although nested it inherits this from the initial package
                ;; in case of nesting two levels need to keep this to know which :js-package-dir this initially came from
                (assoc pkg :js-package-dir js-package-dir))

              (recur (.getParentFile ref-dir)))))))))

(defn find-package-for-require [npm require-from require]
  ;; finds package by require walking down from roots (done in find-package)
  ;; "a/b/c", checks a/package.json, a/b/package.json, a/b/c/package.json
  ;; first package.json wins, nested package.json may come in later when resolving in package
  (let [[start & more] (str/split require #"/")]
    (loop [path start
           more more]

      (let [pkg (find-package-for-require* npm require-from path)]
        (cond
          pkg
          (assoc pkg :match-name path)

          (not (seq more))
          nil

          :else
          (recur (str path "/" (first more)) (rest more))
          )))))

;; resolves foo/bar.js from /node_modules/package/nested/file.js in /node_modules/packages
;; returns foo/bar.js, or in cases where a parent dir is referenced ../foo/bar.js
(defn resolve-require-as-package-path [^File package-dir ^File file ^String require]
  {:pre [(.isFile file)]}
  (->> (.relativize
         (.toPath package-dir)
         (-> file (.getParentFile) (.toPath) (.resolve require)))
       (rc/normalize-name)))

;; /node_modules/foo/bar.js in /node_modules/foo returns ./bar.js
(defn as-package-rel-path [{:keys [^File package-dir] :as package} ^File file]
  (->> (.toPath file)
       (.relativize (.toPath package-dir))
       (str)
       (rc/normalize-name)
       (str "./")))

(comment
  (resolve-require-as-package-path
    (absolute-file (io/file "test-env" "pkg-a"))
    (absolute-file (io/file "test-env" "pkg-a" "nested" "thing.js"))
    "../../index.js")
  )

(defn get-package-override
  [{:keys [js-options] :as npm}
   {:keys [package-name browser-overrides] :as package}
   rel-require]

  (let [package-overrides (:package-overrides js-options)]
    (when (or (and (:use-browser-overrides js-options) (seq browser-overrides))
              (seq package-overrides))

      ;; allow :js-options config to replace files in package by name
      ;; :js-options {:package-overrides {"codemirror" {"./lib/codemirror.js" "./addon/runmode/runmode.node.js"}}
      (or (get-in package-overrides [package-name rel-require])
          ;; FIXME: I'm almost certain that browser allows overriding without extension
          ;; "./lib/some-file":"./lib/some-other-file"
          (get browser-overrides rel-require)))))

;; returns [package file] in case a nested package.json was used overriding package
(defn find-match-in-package [npm {:keys [package-dir package-json] :as package} rel-require]
  (if (= rel-require "./")
    ;; package main, lookup entries
    (let [entries
          (->> (get-in npm [:js-options :entry-keys])
               (map #(get package-json %))
               (remove nil?)
               (into []))]

      (if (seq entries)
        (let [entry-match
              (reduce
                (fn [_ entry]
                  (when-let [match (find-match-in-package npm package entry)]
                    ;; we only want the first one in case more exist
                    (reduced match)))
                nil
                entries)]

          (when (not entry-match)
            (throw (ex-info
                     (str "package in " package-dir " specified entries but they were all missing")
                     {:tag ::missing-entries
                      :entries entries
                      :package-dir package-dir})))

          entry-match)

        ;; fallback for <package>/index.js without package.json
        (let [index (io/file package-dir "index.js")]
          (when (and (.exists index) (.isFile index))
            [package index]))))

    ;; path in package
    ;; rel-require might be ./foo
    ;; need to check ./foo.js and ./foo/package.json or ./foo/index.js
    (let [file (test-file package-dir rel-require)]
      (cond
        (nil? file)
        (when-some [match (test-file-exts npm package-dir rel-require)]
          [package match])

        (.isFile file)
        [package file]

        ;; babel-runtime has a ../core-js/symbol require
        ;; core-js/symbol is a directory
        ;; core-js/symbol.js is a file
        ;; so for each directory first test if there is file by the same name
        ;; then if there is a directory/package.json with a main entry
        ;; then if there is directory/index.js
        (.isDirectory file)
        (if-some [match (test-file-exts npm package-dir rel-require)]
          [package match]
          (let [nested-package-json (io/file file "package.json")]
            (if-not (.exists nested-package-json)
              (when-some [match (test-file-exts npm file "index")]
                [package match])
              (let [nested-package
                    (-> (read-package-json npm nested-package-json)
                        (assoc ::parent package :js-package-dir (:js-package-dir package)))]
                ;; rel-require resolved to a dir, continue with ./ from there
                (find-match-in-package npm nested-package "./")))))

        :else
        (throw
          (ex-info
            (format "found something unexpected %s for %s in %s" file rel-require package-dir)
            {:file file
             :package package
             :rel-require rel-require}))
        ))))

(declare find-resource)

;; expects a require starting with ./ expressing a require relative in the package
;; using this instead of empty string because package exports use it too
(defn find-resource-in-package [npm package require-from rel-require]
  {:pre [(map? package)]}

  (when-not (str/starts-with? rel-require "./")
    (throw (ex-info "invalid require" {:package (:package-name package) :require-from (:resource-id require-from) :rel-require rel-require})))

  (if (:package-exports package)
    (throw (ex-info "tbd" {}))

    ;; default npm resolve
    (when-let [match (find-match-in-package npm package rel-require)]

      ;; might have used a nested package.json, continue from there
      ;; not the root package we started with
      (let [[package file] match
            rel-path (as-package-rel-path package file)
            override (get-package-override npm package rel-path)]

        (cond
          ;; override to disable require, sometimes used to skip certain requires for browser
          (false? override)
          empty-rc

          (and (string? override) (not= override rel-path))
          (or (if (util/is-relative? override)
                (find-resource-in-package npm package require-from override)
                (find-resource npm require-from override))
              (throw (ex-info (format "require %s was overridden to %s but didn't exist in package %s"
                                rel-require override (:package-name package))
                       {:package package
                        :rel-require rel-require})))

          (not (nil? override))
          (throw (ex-info (format "invalid override %s for %s in %s"
                            override rel-require (:package-name package))
                   {:tag ::invalid-override
                    :package-dir (:package-dir package)
                    :require rel-require
                    :override override}))

          :no-override
          (try
            (with-npm-info npm package (get-file-info npm file))
            (catch Exception e
              ;; user may opt to just ignore a require("./something.css")
              (if (and (:ignore-asset-requires (:js-options npm))
                       (asset-require? rel-require))
                empty-rc
                (throw (ex-info "failed to inspect node_modules file"
                         {:tag ::file-info-failed
                          :file file
                          :require-from require-from
                          :require rel-require}
                         e))))))))))

(defn find-resource
  [npm require-from ^String require]
  {:pre [(service? npm)
         (or (nil? require-from)
             (map? require-from))
         (string? require)]}

  (cond
    (util/is-absolute? require)
    (throw (ex-info "absolute require not allowed for node_modules files"
             {:tag ::absolute-path
              :require-from require-from
              :require require}))

    ;; pkg relative require "./foo/bar.js"
    (util/is-relative? require)
    (do (when-not (and require-from (:file require-from))
          (throw (ex-info "relative require without require-from"
                   {:tag ::no-require-from
                    :require-from require-from
                    :require require})))

        (when-not (::package require-from)
          (throw (ex-info "require-from is missing package info"
                   {:tag ::no-package-require-from
                    :require-from require-from
                    :require require})))

        ;; in case of nested packages we may need to recurse since it is valid
        ;; for nested packages to refer to files from the parent
        ;; it is however not valid to refer to relative files outside of that
        ;; thing/foo/bar.js can go to ../bar.js but not ../../thing.js
        (loop [{:keys [^File package-dir] :as package} (::package require-from)]
          (let [{:keys [^File file]} require-from
                rel-require (resolve-require-as-package-path package-dir file require)]

            (if-not (str/starts-with? rel-require "../")
              (find-resource-in-package npm package require-from (str "./" rel-require))
              (if-some [parent (::parent package)]
                (recur parent)
                (throw (ex-info (format "relative require %s from %s outside package %s"
                                  require
                                  (.getAbsolutePath file)
                                  (.getAbsolutePath package-dir))
                         {:package-dir package-dir
                          :file file
                          :require require}))
                )))))

    ;; "package" require
    ;; when "package" is required from within another package its package.json
    ;; has a chance to override what that does, need to check it before actually
    ;; trying to find the package itself
    :package-require
    (let [override
          (when (and require-from (::package require-from) (:use-browser-overrides (:js-options npm)))
            (let [override (get-in require-from [::package :browser-overrides require])]
              ;; might be false, can't use or
              (if-not (nil? override)
                override
                (get node-libs-browser require))))]

      (cond
        ;; common path, no override
        (or (nil? override) (= override require))
        (when-let [{:keys [match-name] :as package} (find-package-for-require npm require-from require)]
          ;; must used the match-name provided by find-package-for-require
          ;; package-name cannot be trusted to match the actual package name
          ;; eg. react-intl-next has react-intl as "name" in package.json
          ;; can't just use the first /, package names can contain / and might be nested
          ;; @foo/bar and @foo/bar/some-nested/package.json are all valid packages
          (if (= require match-name)
            ;; plain package require turns into "./" rel require
            (find-resource-in-package npm package require-from "./")
            ;; strip package/ from package/foo turn it into ./foo
            (let [rel-require (str "." (subs require (count match-name)))]
              (find-resource-in-package npm package require-from rel-require)
              )))

        ;; disabled require
        (false? override)
        empty-rc

        (not (string? override))
        (throw (ex-info (format "invalid browser override in package: %s" require-from)
                 {:require require
                  :require-from require-from
                  :override override}))

        (util/is-relative? override)
        (find-resource-in-package npm (::package require-from) require-from override)

        :else
        (find-resource npm require-from override)
        ))))

(defn shadow-js-require
  ([rc]
   (shadow-js-require rc true))
  ([{:keys [ns require-id resource-config] :as rc} semi-colon?]
   (let [{:keys [export-global export-globals]}
         resource-config

         ;; FIXME: not the greatest idea to introduce two keys for this
         ;; but most of the time there will only be one exported global per resource
         ;; only in jQuery case sometimes we need jQuery and sometimes $
         ;; so it must export both
         globals
         (-> []
             (cond->
               (seq export-global)
               (conj export-global)
               (seq export-globals)
               (into export-globals)))

         opts
         (-> {}
             (cond->
               (seq globals)
               (assoc :globals globals)))]

     (str "shadow.js.require("
          (if require-id
            (pr-str require-id)
            (str "\"" ns "\""))
          ", " (json/write-str opts) ")"
          (when semi-colon? ";")))))

;; FIXME: allow configuration of :extensions :entry-keys
;; maybe some closure opts
(defn start [{:keys [node-modules-dir js-package-dirs] :as config}]
  (let [index-ref
        (atom {:files {}
               :require-cache {}
               :packages {}
               :package-json-cache {}})

        ;; FIXME: share this with classpath
        co
        (doto (CompilerOptions.)
          ;; FIXME: good idea to disable ALL warnings?
          ;; I think its fine since we are just looking for require anyways
          ;; if the code has any other problems we'll get to it when importing
          (.resetWarningsGuard)
          ;; should be the highest possible option, since we can't tell before parsing
          (.setLanguageIn CompilerOptions$LanguageMode/ECMASCRIPT_NEXT))

        cc
        (doto (data/make-closure-compiler)
          (.initOptions co))

        project-dir
        (-> (io/file "")
            (absolute-file))

        js-package-dirs
        (-> []
            (cond->
              (and (not (seq node-modules-dir))
                   (not (seq js-package-dirs)))
              (conj (io/file project-dir "node_modules"))

              (seq node-modules-dir)
              (conj (-> (io/file node-modules-dir)
                        (absolute-file)))

              (seq js-package-dirs)
              (into (->> js-package-dirs
                         (map (fn [path]
                                (-> (io/file path)
                                    (absolute-file))))))))]

    {::service true
     :index-ref index-ref
     :compiler cc
     :compiler-options co
     ;; JVM working dir always
     :project-dir project-dir
     :js-package-dirs js-package-dirs
     :npm-deps (collect-npm-deps-from-classpath)

     ;; browser defaults
     :js-options {:extensions [#_".mjs" ".js" ".json"]
                  :allow-nested-packages true
                  :target :browser
                  :use-browser-overrides true
                  :entry-keys ["browser" "main" "module"]}
     }))

(defn stop [npm])


(defn js-resource-for-global
  "a dependency might come from something already included in the page by other means

   a config like:
   {\"react\" {:type :global :global \"React\"}}

   means require(\"react\") returns the global React instance"
  [require {:keys [global] :as pkg}]
  (let [ns (symbol (ModuleNames/fileToModuleName require))]
    {:resource-id [::global require]
     :resource-name (str "global$" ns ".js")
     :output-name (str ns ".js")
     :global-ref true
     :type :js
     :cache-key [NPM-CACHE-KEY CLOSURE-CACHE-KEY]
     :last-modified 0
     :ns ns
     :provides #{ns}
     :requires #{}
     :deps []
     :source (str "module.exports=(" global ");")}))

(defn js-resource-for-file
  "if we want to include something that is not on npm or we want a custom thing
  {\"react\" {:type :file :file \"path/to/my/react.js\"}}"

  [npm require {:keys [file file-min] :as cfg}]
  (let [mode
        (get-in npm [:js-options :mode] :release)

        file
        (-> (if (and (= :release mode) (seq file-min))
              (io/file file-min)
              (io/file file))
            (absolute-file))]
    (when-not (.exists file)
      (throw (ex-info "file override for require doesn't exist" {:file file :require require :config cfg})))

    (get-file-info npm file)
    ))