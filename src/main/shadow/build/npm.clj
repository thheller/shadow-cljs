(ns shadow.build.npm
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.data.json :as json]
            [cljs.compiler :as cljs-comp]
            [shadow.jvm-log :as log]
            [shadow.build.resource :as rc]
            [shadow.build.log :as cljs-log]
            [shadow.cljs.util :as util :refer (reduce-> reduce-kv->)]
            [shadow.build.data :as data])
  (:import (java.io File)
           (com.google.javascript.jscomp SourceFile CompilerOptions CompilerOptions$LanguageMode)
           (com.google.javascript.jscomp.deps ModuleNames)
           (shadow.build.closure JsInspector)
           [java.nio.file Path]))

(set! *warn-on-reflection* true)

(def NPM-TIMESTAMP
  ;; timestamp to ensure that new shadow-cljs release always invalidate caches
  ;; technically needs to check all files but given that they'll all be in the
  ;; same jar one is enough
  (util/resource-last-modified "shadow/build/npm.clj"))

(def CLOSURE-TIMESTAMP
  ;; timestamp to ensure that new shadow-cljs release always invalidate caches
  ;; technically needs to check all files but given that they'll all be in the
  ;; same jar one is enough
  ;; this is bit ugly but since files are re-compiled by the shadow.build.closure
  ;; namespace (which depends on this one) we need it to properly invalidate
  ;; the cache
  (util/resource-last-modified "shadow/build/closure.clj"))

(defn service? [x]
  (and (map? x) (::service x)))

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
            (.getParentFile file)

            content
            (-> {:package-name name
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
              (.getCanonicalFile))]
      (when (.exists file)
        file))))

(defn test-file-exts ^File [{:keys [extensions] :as npm} ^File dir name]
  (reduce
    (fn [_ ext]
      (when-let [path (test-file dir (str name ext))]
        (when (.isFile path)
          (reduced path))))
    nil
    extensions))

(defn find-package* [{:keys [node-modules-dir] :as npm} package-name]
  ;; this intentionally only checks
  ;; $PROJECT_ROOT/node_modules/package-name
  ;; never
  ;; $PROJECT_ROOT/node_modules/pkg-a/node_modules/pkg-b
  ;; never outside $PROJECT_ROOT
  ;; not allowing anything outside the project because of
  ;; https://github.com/technomancy/leiningen/wiki/Repeatability

  (let [package-dir (io/file node-modules-dir package-name)]
    (when (.exists package-dir)
      (let [package-json-file (io/file package-dir "package.json")]
        (when (.exists package-json-file)
          (read-package-json npm package-json-file))))))

(defn find-package [{:keys [index-ref node-modules-dir] :as npm} package-name]
  {:pre [(string? package-name)
         (seq package-name)]}
  ;; do not cache by package name because builds can end up using different :node-modules-dir settings
  ;; which would lead to incorrect lookups
  (let [package-id (io/file node-modules-dir package-name)]
    (or (get-in @index-ref [:packages package-id])
        (let [pkg-info (find-package* npm package-name)]
          (swap! index-ref assoc-in [:packages package-id] pkg-info)
          pkg-info
          ))))

(defn split-package-require
  "@scoped/thing -> [@scoped/thing nil]
   @scoped/thing/foo -> [@scoped/thing foo]
   unscoped -> [unscoped nil]
   unscoped/foo -> [unscoped foo]"
  [entry]
  (let [slash-idx
        (str/index-of entry "/")

        slash-idx
        (if-not (str/starts-with? entry "@")
          slash-idx
          (str/index-of entry "/" (inc slash-idx)))]

    (if-not slash-idx
      [entry nil]
      [(subs entry 0 slash-idx)
       ;; some requires might have trailing slash, which I guess means it is supposed to use main?
       ;; "string_decoder/"
       (let [suffix (subs entry (inc slash-idx))]
         (when (not= suffix "")
           suffix))])))

(defn find-package-main [{:keys [main-keys] :as npm} {:keys [package-dir package-json] :as package}]
  (let [entries
        (->> main-keys
             (map #(get package-json %))
             (remove nil?)
             (into []))]

    (reduce
      (fn [_ entry]
        ;; test file exts first, so we don't pick a directory over a file
        ;; lib/jsdom
        ;; lib/jsdom.js
        (when-let [file (or (test-file-exts npm package-dir entry)
                            (when-let [file-or-dir (test-file package-dir entry)]
                              (if-not (.isDirectory file-or-dir)
                                file-or-dir
                                (let [index (io/file file-or-dir "index.js")]
                                  (and (.exists index) index)))))]

          ;; we only want the first one in case more exist
          (reduced file)))
      nil
      entries)))

(defn find-package-require [{:keys [node-modules-dir] :as npm} require]
  {:pre [(not (util/is-relative? require))
         (not (util/is-absolute? require))]}

  ;; slightly modified node resolve rules since we don't go "up" the paths
  ;; only node-modules-dir is checked
  ;; eg. /usr/project/node_modules but not /usr/node_modules

  ;; react-dom/server -> react-dom/server.js
  ;; react-dom -> react-dom/package.json -> follow main
  ;; firebase/app
  ;; firebase/app.js doesn't exists
  ;; firebase/app/package.json -> follow main

  ;; first check if node_modules/<require> exists as a file (with or without exts)
  (or (when-let [file (test-file node-modules-dir require)]
        (and (.isFile file) file))
      (test-file-exts npm node-modules-dir require)
      ;; check if node_modules/<require>/package.json exists and follow :main
      (when-let [package (find-package npm require)]
        (find-package-main npm package))
      ;; find node_modules/<require>/index.js
      (let [^File file (io/file node-modules-dir require "index.js")]
        (when (.exists file)
          file))))

(defn find-relative [npm ^File relative-to ^String require]
  (when-not relative-to
    (throw (ex-info
             (format "can only resolve relative require with a file reference: %s" require)
             {:entry require})))

  (let [rel-dir
        (cond
          (.isFile relative-to)
          (.getParentFile relative-to)

          (.isDirectory relative-to)
          relative-to

          :else
          (throw (ex-info (format "can't find %s from %s" require relative-to)
                   {:tag ::find-relative
                    :relative-to relative-to
                    :require require})))

        file
        (test-file rel-dir require)

        ^File file
        (cond
          (not file)
          (test-file-exts npm rel-dir require)

          ;; babel-runtime has a ../core-js/symbol require
          ;; core-js/symbol is a directory
          ;; core-js/symbol.js is a file
          ;; so for each directory first test if there is file by the same name
          (.isDirectory file)
          (or (test-file-exts npm rel-dir require)
              (test-file-exts npm file "index")
              (let [package-json (io/file file "package.json")]
                (when (.exists package-json)
                  ;; node_modules/htmlparser2/lib/Stream.js
                  ;; has a require("../") which resolves to node_modules/htmlparser2
                  ;; which is the root of the package (without a index.js)
                  ;; didn't even know that was allowed but node seems to resolve this
                  ;; by looking at the package and picking the main
                  ;; so we mimick that
                  (find-package-require npm (-> file (.getCanonicalFile) (.getName)))
                  )))

          :else
          file)]

    (when-not (and file (.isFile file))
      (throw (ex-info (format "failed to resolve: %s from %s" require relative-to require)
               {:relative-to relative-to
                :entry require})))

    file))

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
   "string_decoder" "string_decoder"
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
  (let [ns 'shadow.empty]
    {:resource-id [::empty "shadow$empty.js"]
     :resource-name "shadow$empty.js"
     :output-name "shadow$empty.js"
     :type :js
     :cache-key [NPM-TIMESTAMP CLOSURE-TIMESTAMP]
     :last-modified 0
     :ns ns
     :provides #{ns}
     :requires #{}
     :deps '[shadow.js]
     :source ""}))

(defn find-file
  [npm ^File require-from ^String require]
  (cond
    (util/is-absolute? require)
    (throw (ex-info "absolute require not allowed for node_modules files"
             {:tag ::absolute-path
              :require-from require-from
              :require require}
             ))

    (util/is-relative? require)
    (find-relative npm require-from require)

    :else
    (let [require-from-pkg
          (when require-from ;; no overrides for entries
            (find-package-for-file npm require-from))

          browser-override
          (and require-from-pkg
               (get-in require-from-pkg [:browser-overrides require]))

          override
          (if (some? browser-override)
            browser-override
            (get node-libs-browser require))]

      (cond
        (nil? override)
        (find-package-require npm require)

        ;; "canvas": false
        (false? override)
        false

        (not (string? override))
        (throw (ex-info (format "invalid override in package: %s" require-from)
                 {:require require
                  :require-from require-from
                  :override override}))

        ;; jsdom
        ;; "contextify": "./lib/jsdom/contextify-shim.js"
        ;; overrides a package require from within the package to a local file
        (util/is-relative? override)
        (find-relative npm (:package-dir require-from-pkg) override)

        ;; "foo":"bar"
        ;; swap one package with the other
        :else
        (find-package-require npm override)
        ))))

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

(defn get-file-info*
  "extract some basic information from a given file, does not resolve dependencies"
  [{:keys [compiler ^File node-modules-dir ^File project-dir] :as npm} ^File file]
  {:pre [(service? npm)
         (util/is-file-instance? file)
         (.isAbsolute file)]}

  (let [^Path abs-path
        (.toPath project-dir)

        ^Path node-modules-path
        (.toPath node-modules-dir)

        ^Path file-path
        (.toPath file)

        npm-file?
        (.startsWith file-path node-modules-path)

        _ (when-not (or npm-file? (.startsWith file-path abs-path))
            (throw (ex-info (format "files outside the project are not allowed: %s" file-path)
                     {:file file})))

        ;; normalize node_modules files since they may not be at the root of the project
        resource-name
        (if npm-file?
          (->> (.relativize node-modules-path file-path)
               (str)
               (rc/normalize-name)
               (disambiguate-module-name)
               (str "node_modules/"))
          (->> (.relativize abs-path file-path)
               (str)
               (rc/normalize-name)))

        ns (-> (ModuleNames/fileToModuleName resource-name)
               ;; (cljs-comp/munge) ;; FIXME: the above already does basically the same, does it cover everything?
               (symbol))

        last-modified
        (.lastModified file)

        ;; the only reliable way to determine if something belongs to a package
        ;; is by looking for the package.json and parsing the name
        ;; we can't just remember the entry require("react") since that may
        ;; require("./lib/React.js") which also belongs to the react package
        ;; so we must determine this from the file alone not by the way it was required
        {:keys [package-name] :as pkg-info}
        (find-package-for-file npm file)
        ]

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
           :cache-key [NPM-TIMESTAMP CLOSURE-TIMESTAMP last-modified]
           :ns ns
           :provides #{ns}
           :requires #{}
           :source (slurp file)
           :js-deps []}

          ;; FIXME: check if a .babelrc applies and then run source through babel first
          ;; that should take care of .jsx and others if I actually want to support that?
          (let [source
                (slurp file)

                ;; all requires are collected into
                ;; :js-requires ["foo" "bar/thing" "./baz]
                ;; all imports are collected into
                ;; :js-imports ["react"]
                {:keys [js-requires js-imports js-errors js-warnings js-invalid-requires js-language] :as info}
                (JsInspector/getFileInfoMap
                  compiler
                  ;; SourceFile/fromFile seems to leak file descriptors
                  (SourceFile/fromCode (.getAbsolutePath file) source))

                js-deps
                (->> (concat js-requires js-imports)
                     ;; FIXME: not sure I want to go down this road or how
                     ;; require("./some.css") should not break the build though
                     (remove asset-require?)
                     (distinct)
                     (map maybe-convert-goog)
                     (into []))

                js-deps
                (cond-> js-deps
                  (:uses-global-buffer info)
                  (conj 'shadow.js.buffer-global))]

            (when (seq js-errors)
              (throw (ex-info (format "errors in file: %s" (.getAbsolutePath file))
                       (assoc info
                         :resource-name resource-name
                         :tag ::errors))))

            ;; moment.js has require('./locale/' + name); inside a function
            ;; it shouldn't otherwise hurt though
            (when (seq js-invalid-requires)
              (log/info ::js-invalid-requires {:requires js-invalid-requires}))

            (-> info
                (assoc
                  :resource-id [::resource resource-name]
                  :resource-name resource-name
                  ;; not using flat-name since resource-name may contain @scoped/alias
                  :output-name (str ns ".js")
                  :type :js
                  :file file
                  :last-modified last-modified
                  :cache-key [NPM-TIMESTAMP CLOSURE-TIMESTAMP last-modified]
                  :ns ns
                  :provides #{ns}
                  :requires #{}
                  :source source
                  :js-language js-language
                  :js-deps js-deps
                  :deps js-deps))))

        (cond->
          pkg-info
          (assoc :npm-info (select-keys pkg-info [:package-name :version])
                 :package-name package-name)))))

(defn get-file-info [{:keys [index-ref] :as npm} ^File file]
  {:pre [(service? npm)]}
  (or (get-in @index-ref [:files file])
      (let [file-info (get-file-info* npm file)]
        (swap! index-ref assoc-in [:files file] file-info)
        file-info
        )))

(defn find-package-resource [npm require]
  (when-let [file (find-package-require npm require)]
    (get-file-info npm file)))



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
     :cache-key [NPM-TIMESTAMP CLOSURE-TIMESTAMP]
     :last-modified 0
     :ns ns
     :provides #{ns}
     :requires #{}
     :deps []
     :source (str "module.exports=(" global ");")}))

(defn js-resource-for-file
  "if we want to include something that is not on npm or we want a custom thing
  {\"react\" {:type :file :file \"path/to/my/react.js\"}}"

  [npm require {:keys [file file-min] :as cfg} {:keys [mode] :as require-ctx}]
  (let [file
        (-> (if (and (= :release mode) (seq file-min))
              (io/file file-min)
              (io/file file))
            (.getCanonicalFile))]
    (when-not (.exists file)
      (throw (ex-info "file override for require doesn't exist" {:file file :require require :config cfg})))

    (get-file-info npm file)
    ))

(defn find-resource
  [npm ^File require-from ^String require require-ctx]
  {:pre [(service? npm)
         (or (nil? require-from)
             (instance? File require-from))
         (string? require)
         (map? require-ctx)]}

  (let [^File file (find-file npm require-from require)]
    (cond
      (nil? file)
      nil

      (false? file)
      empty-rc

      :else
      (let [{:keys [browser-overrides ^File package-dir] :as pkg}
            (find-package-for-file npm file)

            ;; the package may have "browser":{"./a":"./b"} overrides
            override
            (when (and pkg (seq browser-overrides))
              (let [package-path
                    (.toPath package-dir)

                    rel-name
                    (->> (.toPath file)
                         (.relativize package-path)
                         (str)
                         (rc/normalize-name)
                         (str "./"))]

                ;; FIXME: I'm almost certain that browser allows overriding without extension
                ;; "./lib/some-file":"./lib/some-other-file"
                (get browser-overrides rel-name)))]

        (cond
          ;; good to go, no browser overrides
          (nil? override)
          (get-file-info npm file)

          ;; disabled require
          (false? override)
          empty-rc

          ;; FIXME: is "./lib/some-file.js":"some-package" allowed?
          ;; currently assumes its always a file in the package itself
          (and (string? override)
               (util/is-relative? override))
          (let [override-file
                (-> (io/file package-dir override)
                    (.getCanonicalFile))]

            (when-not (.exists override-file)
              (throw (ex-info "override to file that doesn't exist"
                       {:tag ::invalid-override
                        :require-from require-from
                        :require require
                        :file file
                        :override override
                        :override-file override-file})))
            (get-file-info npm override-file))

          :else
          (throw (ex-info "invalid override"
                   {:tag ::invalid-override
                    :package-dir package-dir
                    :require require
                    :override override})))))))

(defn shadow-js-require [{:keys [ns resource-config] :as rc}]
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

    (str "shadow.js.require(\"" ns "\", " (json/write-str opts) ");")))

;; FIXME: allow configuration of :extensions :main-keys
;; maybe some closure opts
(defn start [{:keys [node-modules-dir] :as config}]
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
            (.getAbsoluteFile))

        node-modules-dir
        (if (seq node-modules-dir)
          (-> (io/file node-modules-dir)
              (.getAbsoluteFile))
          (io/file project-dir "node_modules"))]

    {::service true
     :index-ref index-ref
     :compiler cc
     :compiler-options co
     ;; JVM working dir always
     :project-dir project-dir
     :node-modules-dir node-modules-dir
     :extensions [#_ ".mjs" ".js" ".json"]
     :main-keys [#_#_ "module" "jsnext:main" "browser" "main"]
     }))

(defn stop [npm])
