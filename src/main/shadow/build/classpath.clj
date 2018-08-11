(ns shadow.build.classpath
  (:require [clojure.tools.reader.reader-types :as readers]
            [clojure.tools.reader :as reader]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [cljs.tagged-literals :as tags]
            [shadow.jvm-log :as log]
            [shadow.spec :as ss]
            [shadow.build.resource :as rc]
            [shadow.cljs.util :as util]
            [shadow.build.cache :as cache]
            [shadow.build.ns-form :as ns-form]
            [shadow.build.config :as config]
            [shadow.build.cljs-bridge :as cljs-bridge]
            [shadow.build.npm :as npm])
  (:import (java.io File)
           (java.util.jar JarFile JarEntry)
           (java.net URL)
           (java.util.zip ZipException)
           (shadow.build.closure ErrorCollector JsInspector)
           [java.nio.file Paths Path]
           [com.google.javascript.jscomp CompilerOptions$LanguageMode CompilerOptions SourceFile]
           [com.google.javascript.jscomp.deps ModuleNames]
           [javax.xml.parsers DocumentBuilderFactory]
           [org.w3c.dom Node Element]))

(set! *warn-on-reflection* true)

(def CACHE-TIMESTAMP (util/resource-last-modified "shadow/build/classpath.clj"))

(defn get-classpath []
  ;; in case of a dynamically modified classpath
  ;; we can't use java.class.path so its used last
  ;; shadow-cljs-launcher sets shadow.class.path
  ;; boot-clj sets boot.class-path
  (-> (or (System/getProperty "shadow.class.path")
          (System/getProperty "boot.class.path")
          (System/getProperty "java.class.path"))
      (.split File/pathSeparator)
      (->> (into [] (map io/file)))
      ))

(defn service? [x]
  (and (map? x) (::service x)))

#_(defn inspect-goog [state {:keys [resource-name url] :as rc}]
    (let [errors-ref
          (ErrorCollector.)

          deps-info
          (-> (doto (JsFileParser. errors-ref)
                (.setIncludeGoogBase true))
              (.parseFile resource-name resource-name (slurp url)))

          ;; will be "es6" if it finds import/export, we filter those later
          module-type
          (-> deps-info (.getLoadFlags) (.get "module"))

          deps
          (into [] (map util/munge-goog-ns) (.getRequires deps-info))

          provides
          (into #{} (map util/munge-goog-ns) (.getProvides deps-info))]

      (assoc rc
        :deps deps
        :requires (into #{} deps)
        :provides provides
        :module-type (or module-type "goog"))))

(defn inspect-js [{:keys [compiler] :as state} {:keys [resource-name url] :as rc}]
  ;; avoid parsing cljsjs files since they are standalone bundles
  ;; and can never require/import anything, we don't want to ever include them anyways
  (when-not (str/starts-with? resource-name "cljsjs/")
    (let [source
          (slurp url)

          ;; all requires are collected into
          ;; :js-requires ["foo" "bar/thing" "./baz]
          ;; all imports are collected into
          ;; :js-imports ["react"]
          {:keys [js-requires
                  js-imports
                  js-errors
                  js-warnings
                  js-invalid-requires
                  js-language
                  js-str-offsets
                  goog-module
                  goog-requires
                  goog-provides]
           :as info}
          (JsInspector/getFileInfoMap
            compiler
            ;; SourceFile/fromFile seems to leak file descriptors
            (SourceFile/fromCode resource-name source))

          ns (-> (ModuleNames/fileToModuleName resource-name)
                 (symbol))]

      (cond
        ;; goog.provide('thing')
        ;; goog.require('foo')
        ;; goog.module('some.thing')
        (or (seq goog-module)
            (seq goog-provides)
            (seq goog-requires)
            (= resource-name "goog/base.js"))
        ;; FIXME: support require/import in ClosureJS
        (let [deps
              (-> []
                  (cond->
                    (not= resource-name "goog/base.js")
                    (conj 'goog))
                  (into (map util/munge-goog-ns) goog-requires))]
          (-> rc
              (assoc :type :goog
                     :requires (into #{} deps)
                     :source source
                     :provides
                     (-> #{}
                         ;; for goog files make both namespaces available
                         ;; to match cljs behaviour where _ is always converted to -
                         ;; goog.i18n.DateTimeSymbols_en
                         (into (map symbol) goog-provides)
                         ;; goog.i18n.DateTimeSymbols-en
                         (into (map util/munge-goog-ns) goog-provides))
                     :deps deps)
              (cond->
                (seq goog-module)
                (-> (assoc :goog-module true)
                    (update :provides conj (util/munge-goog-ns goog-module) (symbol goog-module)))

                (= resource-name "goog/base.js")
                (update :provides conj 'goog)
                )))

        ;; require("foo")
        ;; import ... from "foo"
        ;; might be no require/import/exports
        ;; externs files will hit this
        :else
        (let [js-deps
              (->> (concat js-requires js-imports)
                   ;; FIXME: not sure I want to go down this road or how
                   ;; require("./some.css") should not break the build though
                   (remove npm/asset-require?)
                   (distinct)
                   (map npm/maybe-convert-goog)
                   (into []))]

          (-> info
              (merge rc)
              (assoc :source source
                     :type :js
                     :classpath true
                     :ns ns
                     :provides #{ns}
                     :requires #{}
                     :deps js-deps)))
        ))))

(defn inspect-cljs
  "looks at the first form in a .cljs file, analyzes it if (ns ...) and returns the updated resource
   with ns-related infos"
  [{:keys [url resource-name macros-ns] :as rc}]
  (try
    (let [{:keys [name deps requires] :as ast}
          (cljs-bridge/get-resource-info
            resource-name
            (or (:source rc) ;; nREPL load-file supplies source
                (slurp url))
            ;; since the classpath instance is shared we use the default
            ;; on resolve the resource should be inspected again
            #{:cljs})

          provide-name
          (if-not macros-ns
            name
            (symbol (str name "$macros")))]

      (-> rc
          (assoc
            :ns-info (dissoc ast :env)
            :ns provide-name
            :provides #{provide-name}
            :requires (into #{} (vals requires))
            :macro-requires
            (-> #{}
                (into (-> ast :require-macros vals))
                (into (-> ast :use-macros vals)))
            :deps deps)
          (cond->
            macros-ns
            (assoc :macros-ns true))))
    (catch Exception e
      ;; when the ns form fails to parse or any other related error occurs
      ;; we guess the ns from the filename and proceed to the real error
      ;; occurs again when actually trying to compile
      ;; otherwise the error ends up unrelated in the log
      ;; with the build error just being a generic "not available"

      (log/debug-ex e ::inspect-failure {:macro-ns macros-ns :url url})
      (let [guessed-ns (util/filename->ns resource-name)]
        (assoc rc
          :ns guessed-ns
          :inspect-error true
          :inspect-error-data (ex-data e)
          :provides #{guessed-ns}
          :requires '#{goog cljs.core}
          :macro-requires #{}
          :deps '[goog cljs.core])
        ))))

(defn inspect-resource
  [state {:keys [resource-name url] :as rc}]
  (cond
    (util/is-js-file? resource-name)
    (inspect-js state rc)

    (util/is-cljs-file? resource-name)
    (->> (assoc rc :type :cljs)
         (inspect-cljs))

    :else
    (throw (ex-info "cannot identify as cljs resource" {:resource-name resource-name :url (str url)}))))

(defn should-ignore-resource?
  [{:keys [ignore-patterns] :as state} resource-name]
  (loop [patterns ignore-patterns]
    (if-let [pattern (first patterns)]
      (if (re-find pattern resource-name)
        true
        (recur (rest patterns)))
      false
      )))

(defn process-deps-cljs [cp ^File source-path resources]
  {:pre [(sequential? resources)
         (util/file? source-path)]}

  (let [index
        (->> resources
             (map (juxt :resource-name identity))
             (into {}))

        data-readers
        (get index "data_readers.cljc")

        index
        (dissoc index "data_readers.cljc")

        deps-cljs
        (get index "deps.cljs")]

    (if (nil? deps-cljs)
      {:foreign-libs []
       :externs []
       :resources (vec (vals index))
       :data-readers data-readers}

      (let [{:keys [externs foreign-libs] :as deps-cljs}
            (-> (slurp (:url deps-cljs))
                (edn/read-string))

            _ (when-not (s/valid? ::config/deps-cljs deps-cljs)
                (throw (ex-info "invalid deps.cljs"
                         (assoc (s/explain-data ::config/deps-cljs deps-cljs)
                           :tag ::deps-cljs
                           :deps deps-cljs
                           :source-path source-path))))

            get-index-rc
            (fn [index resource-name]
              (or (get index resource-name)
                  (throw (ex-info (format "%s deps.cljs refers to file not in jar: %s" source-path resource-name)
                           {:tag ::deps-cljs :source-path source-path :name resource-name}))))

            foreign-rcs
            (->> foreign-libs
                 (map-indexed
                   (fn [idx {:keys [externs provides requires file file-min global-exports] :as foreign-lib}]
                     (when-not (seq provides)
                       (throw (ex-info "deps.cljs foreign-lib without provides"
                                {:tag ::deps-cljs :source-path source-path})))

                     (let [file-rc
                           (when file
                             (get-index-rc index file))

                           file-min-rc
                           (when file-min
                             (get-index-rc index file-min))

                           extern-rcs
                           (into [] (map #(get-index-rc index %)) externs)

                           ;; requires and provides are strings :(
                           deps
                           (into [] (map symbol) requires)

                           provides
                           (set (map symbol provides))

                           extern-rcs
                           (->> externs
                                (map #(get-index-rc index %))
                                (into []))

                           resource-name
                           (or file file-min)
                           ]

                       (-> {:resource-id [::foreign (.getAbsolutePath source-path) idx]
                            :resource-name resource-name
                            :type :foreign
                            :output-name (util/flat-js-name resource-name)
                            ;; :file, :file-min and :externs can all invalidate the foreign lib
                            :cache-key (-> [(:cache-key file-rc)
                                            (:cache-key file-min-rc)]
                                           (into (map :cache-key extern-rcs)))

                            ;; FIXME: this is bad, it must choose one but loses the other in the process
                            :last-modified (or (:last-modified file-rc)
                                               (:last-modified file-min-rc))
                            :requires (set deps)
                            :provides provides
                            :deps deps
                            :externs extern-rcs}
                           (cond->
                             global-exports
                             (assoc :global-exports global-exports)
                             file-rc
                             (assoc :url (:url file-rc))
                             file-min-rc
                             (assoc :url-min (:url file-min-rc))))

                       )))
                 (into []))

            index
            (dissoc index "deps.cljs")

            things-to-drop
            (reduce
              (fn [x {::keys [externs file file-min]}]
                (-> (into x externs)
                    (cond->
                      file
                      (conj file)
                      file-min
                      (conj file-min))))
              (into #{"deps.cljs"} externs)
              foreign-libs)

            extern-rcs
            (into [] (map #(get-index-rc index %)) externs)

            index
            (reduce dissoc index things-to-drop)]

        {:resources (vec (vals index))
         :foreign-libs foreign-rcs
         :data-readers data-readers
         :externs extern-rcs}
        ))))

(defn pom-info-for-jar [^File file]
  (when (.isFile file)
    (let [pom-file (io/file
                     (.getParentFile file)
                     (str/replace (.getName file) ".jar" ".pom"))]
      (when (.exists pom-file)
        (try
          (let [doc
                (-> (DocumentBuilderFactory/newInstance)
                    (.newDocumentBuilder)
                    (.parse pom-file))

                el
                (.getDocumentElement doc)

                child-nodes
                (.getChildNodes el)

                {:keys [artifact-id group-id version] :as pom-info}
                (loop [x 0
                       info {}]
                  (if (>= x (.getLength child-nodes))
                    info
                    (let [^Element node (.item child-nodes x)]
                      (recur
                        (inc x)
                        (case (.getNodeName node)
                          "artifactId"
                          (assoc info :artifact-id (symbol (.getTextContent node)))

                          "groupId"
                          (assoc info :group-id (symbol (.getTextContent node)))

                          ;; POM inheritance ... no idea how that works
                          ;; but core.cache and others don't have their own groupId
                          ;; guessing they inherit from the parent
                          "parent"
                          (let [pgid
                                (-> node
                                    (.getElementsByTagName "groupId")
                                    (.item 0)
                                    (.getTextContent)
                                    (symbol))]
                            (-> info
                                (assoc :parent-group-id pgid)
                                (cond->
                                  (not (contains? info :group-id))
                                  (assoc :group-id pgid))))

                          "version"
                          (assoc info :version (.getTextContent node))

                          "name"
                          (assoc info :name (.getTextContent node))

                          "url"
                          (assoc info :url (.getTextContent node))

                          "description"
                          (assoc info :description (.getTextContent node))

                          info
                          )))))

                id
                (if (= artifact-id group-id)
                  artifact-id
                  (symbol (str group-id) (str artifact-id)))]

            (assoc pom-info
              :id id
              :coordinate [id version]))

          (catch Exception e
            (log/debug-ex e ::pom-error {:pom pom-file})
            nil))))))

(comment
  (doseq [x (get-classpath)]
    (prn (pom-info-for-jar x))))

(defn find-jar-resources* [cp ^File file]
  (try
    (let [jar-path
          (.getCanonicalPath file)

          jar-file
          (JarFile. file)

          last-modified
          (.lastModified file)

          pom-info
          (pom-info-for-jar file)

          entries
          (.entries jar-file)]

      (loop [result (transient [])]
        (if (not (.hasMoreElements entries))
          (persistent! result)

          ;; next entry
          (let [^JarEntry jar-entry (.nextElement entries)
                name (.getName jar-entry)]
            (if (or (not (util/is-cljs-resource? name))
                    (should-ignore-resource? cp name))
              (recur result)
              (let [url (URL. (str "jar:file:" jar-path "!/" name))]
                (-> result
                    (conj! (-> {:resource-id [::resource name]
                                :resource-name (rc/normalize-name name)
                                :cache-key [last-modified]
                                :last-modified last-modified
                                :url url
                                :from-jar true}
                               (cond->
                                 pom-info
                                 (assoc :pom-info pom-info))))
                    (recur)
                    )))))))

    (catch ZipException e
      (log/debug-ex e ::bad-jar {:file file})
      {})

    (catch Exception e
      (throw (ex-info (str "failed to generate jar manifest for file: " file) {:file file} e)))
    ))

(defn set-output-name
  "sets the :output-name for each given resource
   demo/foo.cljs becomes demo.foo.js
   JS inputs are named to match their name generated by closure to avoid conclicts
   demo/foo.js becomes module$demo$foo.js"
  ;; FIXME: makes .js files annoying to use directly in npm-module
  [{:keys [type ns resource-name] :as rc}]
  (assoc rc
    :output-name
    (case type
      :goog
      (util/flat-filename resource-name)
      :cljs
      (util/flat-js-name resource-name)
      :js
      (str ns ".js")
      )))

(defmethod log/log-msg ::resource-inspect [_ {:keys [loc]}]
  (format "failed to inspect resource \"%s\", it will not be available." loc))

(defn inspect-resources [cp {:keys [resources] :as contents}]
  (assoc contents :resources
                  (->> resources
                       (map (fn [src]
                              (try
                                (inspect-resource cp src)
                                (catch Exception e
                                  ;; don't warn with stacktrace
                                  (log/warn ::resource-inspect
                                    {:loc (or (:file src)
                                              (:url src)
                                              (:resource-name src))})
                                  ;; debug log should contain stacktrace
                                  (log/debug-ex e
                                    ::inspect-ex
                                    {:log (or (:file src)
                                              (:url src)
                                              (:resource-name src))})
                                  nil))))
                       (remove nil?)
                       (map set-output-name)
                       (into [])
                       )))

(defn process-root-contents [cp source-path root-contents]
  {:pre [(sequential? root-contents)
         (util/file? source-path)]}

  (->> (process-deps-cljs cp source-path root-contents)
       (inspect-resources cp)))

(defn find-jar-resources
  [{:keys [manifest-cache-dir] :as cp} ^File jar-file]
  (let [manifest-name
        (str (.lastModified jar-file) "-" (.getName jar-file) ".manifest")

        mfile
        (io/file manifest-cache-dir manifest-name)]

    (or (when (and (.exists mfile)
                   (>= (.lastModified mfile) (.lastModified jar-file))
                   (>= (.lastModified mfile) CACHE-TIMESTAMP))
          (try
            (let [cache (cache/read-cache mfile)]
              ;; user downloads version 2.0.0 runs it
              ;; upgrades to latest version release a day ago
              ;; last-modified of cache is higher that release data
              ;; so the initial check succeeds because >= is true
              ;; comparing them to be equal ensures that new version
              ;; will invalidate the cache
              (when (= CACHE-TIMESTAMP (::CACHE-TIMESTAMP cache))
                cache))
            (catch Exception e
              (log/info-ex e ::manifest-ex {:file mfile})
              nil)))

        (let [jar-contents
              (->> (find-jar-resources* cp jar-file)
                   (process-root-contents cp jar-file))]
          (io/make-parents mfile)
          (cache/write-file mfile (assoc jar-contents ::CACHE-TIMESTAMP CACHE-TIMESTAMP))
          jar-contents))))

(defn make-fs-resource [^File file name]
  (let [last-mod
        (if (.exists file)
          (.lastModified file)
          (System/currentTimeMillis))]

    {:resource-id [::resource name]
     :resource-name name
     :cache-key [last-mod]
     :last-modified last-mod
     :file file
     :url (.toURL file)}))

(defn find-fs-resources*
  [cp ^File root]
  (let [root-path (.getCanonicalPath root)
        root-len (inc (count root-path))]
    (into []
      (for [^File file (file-seq root)
            :when (and (.isFile file)
                       (not (.isHidden file))
                       (util/is-cljs-resource? (.getName file)))
            :let [file (.getCanonicalFile file)
                  abs-path (.getCanonicalPath file)
                  name (-> abs-path
                           (.substring root-len)
                           (rc/normalize-name))]
            :when (not (should-ignore-resource? cp name))]

        (make-fs-resource file name)
        ))))

(defn find-fs-resources [cp ^File root]
  (->> (find-fs-resources* cp root)
       (process-root-contents cp root)))

(defn find-resources [cp ^File file]
  (if (util/is-jar? (.getName file))
    (find-jar-resources cp file)
    (find-fs-resources cp file)))

(defn should-exclude-classpath [exclude ^File file]
  (let [abs-path (.getAbsolutePath file)]
    (boolean (some #(re-find % abs-path) exclude))))

(defn get-classpath-entries [{:keys [index-ref] :as cp}]
  (let [{:keys [classpath-excludes]} @index-ref]
    (->> (get-classpath)
         ;; apparently linux distros put jars on the classpath that don't exist?
         ;; https://github.com/shadow-cljs/shadow-cljs.github.io/pull/19
         (filter #(.exists ^File %))
         (remove #(should-exclude-classpath classpath-excludes %))
         (map #(.getCanonicalFile ^File %))
         (distinct)
         (into []))))

(defn index-rc-remove [index resource-name]
  {:pre [(string? resource-name)]}
  (let [{:keys [provides file] :as current} (get-in index [:sources resource-name])]
    (if-not current
      index

      (-> index
          (util/reduce->
            (fn [state provide]
              (update-in state [:provide->source] dissoc provide))
            provides)
          (update :sources dissoc resource-name)
          (cond->
            file
            (update :file->name dissoc file))))))

(defn is-same-resource? [a b]
  (and (= (:resource-id a) (:resource-id b))
       (= (:resource-name a) (:resource-name b))
       (= (:url a) (:url b))))

(defn index-rc-add [state {:keys [resource-name file provides] :as rc}]
  (-> state
      (assoc-in [:sources resource-name] rc)
      (util/reduce->
        (fn [state provide]
          (assoc-in state [:provide->source provide] resource-name))
        provides)
      (cond->
        file
        (assoc-in [:file->name file] resource-name))))

(defmethod log/log-msg ::duplicate-resource [_ {:keys [resource-name url-a url-b]}]
  (format "duplicate resource %s on classpath, using %s over %s" resource-name url-a url-b))

(defmethod log/log-msg ::filename-violation [_ {:keys [ns expected actual]}]
  (format "filename violation for ns %s, got: %s expected: %s (or .cljc)" ns actual expected))

(defmethod log/log-msg ::provide-conflict [_ {:keys [provides resource-name conflicts]}]
  (format "provide conflict for %s provided by %s and %s" provides resource-name conflicts))


(defn index-rc-merge-js
  [index {:keys [type ns resource-name provides url file] :as rc}]
  (cond
    ;; do not merge files that are already present from a different source path
    ;; same rules as other sources
    (when-let [existing (get-in index [:sources resource-name])]
      (not (is-same-resource? rc existing)))
    (let [conflict (get-in index [:sources resource-name])]
      (when-not (and (:from-jar rc)
                     (not (:from-jar conflict)))
        ;; only warn when jar conflicts with jar, fs is allowed to override files in jars
        (log/info ::duplicate-resource {:resource-name resource-name
                                        :url-a (:url conflict)
                                        :url-b (:url rc)}))
      index)

    :no-conflict
    (index-rc-add index rc)))

(defn index-rc-merge
  [index {:keys [type ns resource-name provides url] :as rc}]
  (cond
    ;; sanity check for development
    (not (rc/valid-resource? rc))
    (throw (ex-info "invalid resource" {}))

    (= :js type)
    (index-rc-merge-js index rc)

    ;; ignore process/env.cljs shim, we have our own
    (and (= :cljs type)
         (= 'process.env ns)
         (= "process/env.cljs" resource-name))
    index

    ;; some cljs libs contain a :none compiled version of cljs.core in a jar
    ;; only cljs/core.cljs is allowed to provide cljs.core
    (and (contains? (:provides rc) 'cljs.core)
         (not= resource-name "cljs/core.cljs"))
    index

    ;; do not merge files that don't have the expected path for their ns
    ;; ONLY enforce this for files in jars, since they sometimes contain
    ;; invalid files which should never be used.
    ;; for actual files the resource should be available but display a proper warning
    ;; so it can be fixed. not including the file displays confusing errors otherwise.
    (and (= :cljs (:type rc))
         (:from-jar rc)
         (symbol? (:ns rc))
         (let [expected-cljs (util/ns->cljs-filename (:ns rc))
               expected-cljc (str/replace expected-cljs #".cljs$" ".cljc")]
           (not (or (= resource-name expected-cljs)
                    (= resource-name expected-cljc)
                    ))))

    (do (log/info ::filename-violation {:ns (:ns rc)
                                        :actual resource-name
                                        :expected (util/ns->cljs-filename (:ns rc))})

        ;; still want to remember the resource so it doesn't get detected as new all the time
        ;; remove all provides, otherwise it might end up being used despite the invalid name
        ;; enforce this behavior since the warning might get overlooked easily
        (let [invalid-src (assoc rc
                            :provides #{}
                            :requires #{}
                            :deps [])]
          (index-rc-add index invalid-src)))

    ;; do not merge files that are already present from a different source path
    (when-let [existing (get-in index [:sources resource-name])]
      (not (is-same-resource? rc existing)))
    (let [conflict (get-in index [:sources resource-name])]
      (when-not (and (:from-jar rc)
                     (not (:from-jar conflict)))
        ;; only warn when jar conflicts with jar, fs is allowed to override files in jars
        (log/info ::duplicate-resource {:resource-name resource-name
                                        :url-a (:url conflict)
                                        :url-b (:url rc)}))
      index)

    ;; now we need to handle conflicts for cljc/cljs files
    ;; only use cljs if both exist
    :valid-resource
    (let [cljc?
          (util/is-cljc? resource-name)

          cljc-name
          (when (util/is-cljs? resource-name)
            (str/replace resource-name #"cljs$" "cljc"))

          cljs-name
          (when cljc?
            (str/replace resource-name #"cljc$" "cljs"))

          lookup-xf
          (comp (map #(get-in index [:provide->source %]))
            (remove nil?))

          existing-provides
          (into #{} lookup-xf provides)

          expected-name
          (util/ns->cljs-filename (:ns rc))

          expected-filename?
          (and (= :cljs (:type rc))
               (symbol? (:ns rc))
               (let [expected-cljc (str/replace expected-name #".cljs$" ".cljc")]
                 (or (= resource-name expected-name)
                     (= resource-name expected-cljc))))
          rc
          (cond-> rc
            (not expected-filename?)
            (assoc :unexpected-name true
                   :expected-name expected-name))]

      (cond
        ;; don't merge .cljc file if a .cljs of the same name exists
        (and cljc? (contains? (:sources index) cljs-name))
        index

        ;; if a .cljc exists for a .cljs file
        ;; overrides provides from .cljc with provides in .cljs
        (and (util/is-cljs? resource-name) (contains? (:sources index) cljc-name))
        (-> index
            (index-rc-remove cljc-name)
            (index-rc-add rc))

        ;; ensure that files do not have conflicting provides
        (and (seq existing-provides)
             (not (every? #(is-same-resource? rc (get-in index [:sources %])) existing-provides)))
        (do (log/warn
              ::provide-conflict
              {:provides provides
               :resource-name resource-name
               :conflicts
               (reduce
                 (fn [m src-name]
                   (let [{:keys [source-path provides]}
                         (get-in index [:sources src-name])]
                     (assoc m (str source-path "/" src-name) provides)))
                 {}
                 existing-provides)})
            index)

        :no-conflict
        (index-rc-add index rc)
        ))))

(defn index-path-merge [state source-path {:keys [externs foreign-libs resources] :as dir-contents}]
  (-> state
      (update :source-paths conj source-path)
      (update :deps-externs assoc source-path externs)
      (util/reduce-> index-rc-merge resources)
      ;; (util/reduce-> index-rc-merge foreign-libs)
      ))

(defn index-path*
  [index path]
  {:pre [(util/is-file-instance? path)]}
  (let [dir-contents (find-resources index path)]
    (index-path-merge index path dir-contents)))

(defn index-file-add [index ^File source-path ^File file]
  (let [abs-file
        (.getAbsoluteFile file)

        src-path
        (-> (.getAbsoluteFile source-path)
            (.toPath))

        resource-name
        (-> (.relativize src-path (.toPath abs-file))
            (str)
            (rc/normalize-name))]

    ;; FIXME: only allow adding .cljs .cljc .js files
    ;; don't add files the file indexer would ignore
    (if (should-ignore-resource? index resource-name)
      index
      (let [rc
            (->> (make-fs-resource abs-file resource-name)
                 (inspect-resource index)
                 (set-output-name))]

        (index-rc-merge index rc)
        ))))

(defn index-file-remove [index source-path ^File file]
  (let [abs-file (.getAbsoluteFile file)
        resource-name (get-in index [:file->name abs-file])]
    (if-not resource-name
      index
      (index-rc-remove index resource-name)
      )))

(defn start [cache-root]
  (let [co
        (doto (CompilerOptions.)
          ;; FIXME: good idea to disable ALL warnings?
          ;; I think its fine since we are just looking for require anyways
          ;; if the code has any other problems we'll get to it when importing
          (.resetWarningsGuard)
          ;; should be the highest possible option, since we can't tell before parsing
          (.setLanguageIn CompilerOptions$LanguageMode/ECMASCRIPT_NEXT))

        cc ;; FIXME: error reports still prints to stdout
        (doto (com.google.javascript.jscomp.Compiler.)
          (.disableThreads)
          (.initOptions co))

        index
        {:ignore-patterns
         #{#"node_modules/"
           ;; cljs.core aot
           #"\.aot\.js$"
           ;; closure library test files
           #"^goog/demos/"
           #"^goog/(.+)_test\.js$"
           ;; closure compiler support and test files
           #"^com/google/javascript"
           ;; way too many jars contain a public folder
           #"^public/"
           ;; these files fail to parse correctly but we don't need them anyways
           #"^jdk/nashorn/*"
           #"goog/transpile\.js"
           ;; just in case the :output-dir of a dev build is on the classpath
           #"/cljs-runtime/"}

         :classpath-excludes
         [#"resources(/?)$"
          #"classes(/?)$"
          #"java(/?)$"]

         :manifest-cache-dir
         (io/file cache-root "jar-manifest")

         ;; sym->name
         :provide->source {}

         ;; file -> resource-name
         :file->name {}

         :source-paths #{}

         :deps-externs {}

         :compiler cc
         :compiler-options co

         ;; resource-name -> resource
         :sources {}}]

    {::service true
     ;; FIXME: maybe use an agent?
     ;; few of the functions working on the index will touch the filesystem
     ;; they only read so retries are fine but maybe agent would be good too
     ;; they are however annoying to coordinate and I typically want to
     ;; wait before moving on
     :compiler-options co
     :compiler cc
     :index-ref (atom index)}))

(defn stop [cp])

;; API

(defonce ^:private classpath-lock (Object.))

(defn index-classpath
  ([cp]
   (index-classpath cp (get-classpath-entries cp)))
  ([{:keys [index-ref] :as cp} paths]
   {:pre [(service? cp)]}
   (locking classpath-lock
     (swap! index-ref #(reduce index-path* % paths)))
   cp))

(defn find-resource-for-provide
  [{:keys [index-ref] :as cp} provide-sym]
  {:pre [(service? cp)
         (symbol? provide-sym)]}
  (let [index @index-ref]
    (when-let [src-name (get-in index [:provide->source provide-sym])]
      (or (get-in index [:sources src-name])
          (throw (ex-info "missing classpath source" {:src-name src-name :sym provide-sym}))
          ))))

(defn get-provided-names [{:keys [index-ref] :as cp}]
  (-> @index-ref :provide->source keys set))

(defn find-resource-by-name
  "returns nil if name is not on the classpath (or was filtered)"
  [{:keys [index-ref] :as cp} name]
  {:pre [(service? cp)
         (string? name)]}
  (get-in @index-ref [:sources name]))

(defn find-resource-by-file
  "returns nil if file is not registered on the classpath"
  [{:keys [index-ref] :as cp} ^File file]
  {:pre [(service? cp)
         (util/is-file-instance? file)]}

  (let [abs-file (.getAbsoluteFile file)
        index @index-ref]
    (when-let [src-name (get-in index [:file->name abs-file])]
      (get-in index [:sources src-name]))))

(defn get-deps-externs [{:keys [index-ref] :as cp}]
  (:deps-externs @index-ref))

(defn get-source-provides
  "returns the set of provided symbols from sources not in jars"
  [{:keys [index-ref] :as cp}]
  (->> (:sources @index-ref)
       (vals)
       (remove :from-jar)
       (filter #(= :cljs (:type %)))
       (map :provides)
       (reduce set/union #{})))

;; FIXME: all these screw up with a file is modified that is referenced in deps.cljs
;; FIXME: should these throw when trying to add files not on classpath?
;; for now only classpath update calls these so it should be fine

(defn file-add [{:keys [index-ref] :as cp} source-path file]
  {:pre [(service? cp)]}
  (swap! index-ref index-file-add source-path file))

(defn file-remove [{:keys [index-ref] :as cp} source-path file]
  {:pre [(service? cp)]}
  (swap! index-ref index-file-remove source-path file))

(defn file-update [{:keys [index-ref] :as cp} source-path file]
  {:pre [(service? cp)]}
  (swap! index-ref
    (fn [index]
      (-> index
          (index-file-remove source-path file)
          (index-file-add source-path file)))))

(defn find-resources-using-ns
  [{:keys [index-ref] :as cp} ns-sym]
  {:pre [(symbol? ns-sym)]}
  (->> (:sources @index-ref)
       (vals)
       (filter (fn [{:keys [ns requires]}]
                 (and (contains? requires ns-sym)
                      ;; FIXME: this namespace should never be used anywhere
                      (not= ns 'shadow.build.cljs-hacks))))
       (into #{})))

(defn get-all-resources
  [{:keys [index-ref] :as cp}]
  (->> (:sources @index-ref)
       (vals)))

(defn find-js-resource
  ;; absolute require "/some/foo/bar.js" or "/some/foo/bar"
  ([{:keys [index-ref] :as cp} ^String require]
   (let [index @index-ref
         require (cond-> require (str/starts-with? require "/") (subs 1))]
     (or (get-in index [:sources require])
         (get-in index [:sources (str require ".js")])
         ;; FIXME: I'm not sure this is a good idea
         (get-in index [:sources (str require "/index.js")])
         )))

  ;; relative require "./foo.js" from another rc
  ([cp {:keys [resource-name] :as require-from} ^String require]
   (when-not require-from
     (throw (ex-info "relative requires only allowed in files" {:require require})))

   (let [path
         (-> (Paths/get resource-name (into-array String []))
             (.getParent)
             (.resolve require)
             (.normalize)
             (.toString)
             (rc/normalize-name))]

     (find-js-resource cp path)
     )))

(comment
  ;; FIXME: implement correctly



  (defn find-dependents-for-names [state source-names]
    (->> source-names
         (map #(get-in state [:sources % :provides]))
         (reduce set/union)
         (map #(find-dependent-names state %))
         (reduce set/union)
         (into #{})))

  (defn find-resources-using-macro
    "returns a set of names using the macro ns"
    [state macro-ns]
    (let [direct-dependents
          (->> (:sources state)
               (vals)
               (filter (fn [{:keys [macro-namespaces] :as rc}]
                         (contains? macro-namespaces macro-ns)))
               (map :name)
               (into #{}))]

      ;; macro has a companion .cljs file
      ;; FIXME: should check if that file actually self references
      (if (get-resource-for-provide state macro-ns)
        (-> (find-dependent-names state macro-ns)
            (set/union direct-dependents))
        direct-dependents
        ))))



