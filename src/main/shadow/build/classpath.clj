(ns shadow.build.classpath
  (:require
    [clojure.tools.reader.reader-types :as readers]
    [clojure.tools.reader :as reader]
    [clojure.edn :as edn]
    [clojure.spec.alpha :as s]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [cljs.tagged-literals :as tags]
    [shadow.debug :as dbg :refer (?> ?-> ?->>)]
    [shadow.jvm-log :as log]
    [shadow.spec :as ss]
    [shadow.build.resource :as rc]
    [shadow.cljs.util :as util]
    [shadow.build.cache :as cache]
    [shadow.build.ns-form :as ns-form]
    [shadow.build.config :as config]
    [shadow.build.cljs-bridge :as cljs-bridge]
    [shadow.build.npm :as npm]
    [shadow.build.data :as data])
  (:import (java.io File)
           (java.util.jar JarFile JarEntry Attributes$Name)
           (java.net URL URLDecoder)
           (java.util.zip ZipException)
           (shadow.build.closure JsInspector)
           [java.nio.file Paths Path]
           [com.google.javascript.jscomp CompilerOptions$LanguageMode CompilerOptions SourceFile]
           [com.google.javascript.jscomp.deps ModuleNames]
           [javax.xml.parsers DocumentBuilderFactory]
           [org.w3c.dom Element]))

(set! *warn-on-reflection* true)

(def CACHE-TIMESTAMP (util/resource-last-modified "shadow/build/classpath.clj"))

(defn get-classpath []
  ;; in case of a dynamically modified classpath
  ;; we can't use java.class.path so its used last
  ;; shadow-cljs-launcher sets shadow.class.path
  ;; boot-clj sets boot.class-path
  (let [classpath-entries
        (-> (or (System/getProperty "shadow.class.path")
                (System/getProperty "boot.class.path")
                (System/getProperty "java.class.path"))
            (.split File/pathSeparator)
            (->> (into [] (map io/file))))]

    ;; java -cp helper.jar foo.main
    ;; java supports a single jar with a manifest Class-Path entry to further
    ;; expand the classpath. sometimes used when the classpath is too long
    ;; on Windows to be invoked as a command. the original jar will likely be empty
    (if (not= 1 (count classpath-entries))
      classpath-entries
      (let [^File entry (nth classpath-entries 0)]
        ;; don't think it is even possible to get here
        ;; with one classpath entry that isn't a jar but who knows
        (if-not (.endsWith (-> entry (.getName) (.toLowerCase)) ".jar")
          classpath-entries
          (let [jar-file (JarFile. entry)
                manifest (.getManifest jar-file)
                cp-from-jar
                (-> (.getMainAttributes manifest)
                    (.getValue Attributes$Name/CLASS_PATH))]
            ;; no Class-Path:, might be uberjar?
            (if-not cp-from-jar
              classpath-entries
              (->> (str/split cp-from-jar #"\s")
                   ;; spec says they must he urls, so assuming file:/...
                   ;; spec also allows remote urls but unlikely we have that?
                   ;; FIXME: should likely check just in case
                   (map (fn [s] (-> (URL. s) (.getPath) (io/file))))
                   (vec)))))))))

(defn service? [x]
  (and (map? x) (::service x)))

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
                  goog-module-legacy-namespace
                  goog-requires
                  goog-provides]
           :as info}
          (JsInspector/getFileInfoMap
            compiler
            ;; SourceFile/fromFile seems to leak file descriptors
            (SourceFile/fromCode resource-name source))

          rc (assoc rc :inspect-info info)]

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
                     :goog-src (or (= resource-name "goog/base.js")
                                   (and (seq goog-provides) (every? #(str/starts-with? % "goog.") goog-provides)))
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
                (-> (assoc :ns (util/munge-goog-ns goog-module)
                           :goog-module goog-module
                           :goog-module-legacy-namespace goog-module-legacy-namespace
                           :goog-src (str/starts-with? goog-module "goog."))
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
                   (distinct)
                   (map npm/maybe-convert-goog)
                   (into []))

              js-deps
              (cond-> js-deps
                (:uses-global-buffer info)
                (conj "buffer")
                (:uses-global-process info)
                (conj "process"))

              ns
              (-> (ModuleNames/fileToModuleName resource-name)
                  (symbol))]


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
            (assoc :macros-ns true :source-ns name))))
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
                          (assoc info :description (str/trim (.getTextContent node)))

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

(defn get-jar-info* [jar-path]
  (let [file (io/file jar-path)]
    (when-not (and (.exists file) (.isFile file))
      (throw (ex-info "expected to find jar file but didn't" {:jar-path jar-path})))
    (let [pom-info (pom-info-for-jar file)
          checksum (data/sha1-file file)]
      {:checksum checksum
       :pom-info pom-info
       :last-modified (.lastModified file)})))

(def get-jar-info (memoize get-jar-info*))

;; jar:file:/C:/Users/thheller/.m2/repository/org/clojure/clojurescript/1.10.773/clojurescript-1.10.773.jar!/cljs/core.cljs
(defn get-jar-info-for-url [^URL rc-url]
  (let [path (.getFile rc-url)]
    (when-not (str/starts-with? path "file:")
      (throw (ex-info "expected to file: in jar url but didn't" {:rc-url rc-url})))
    (let [idx (str/index-of path "!/")]
      (when-not idx
        (throw (ex-info "expected to find !/ in jar url but didn't" {:rc-url rc-url})))
      (let [jar-path (URLDecoder/decode (subs path 5 idx) "utf-8")]
        (get-jar-info jar-path)))))

(defn make-jar-resource [^URL rc-url name]
  (let [{:keys [checksum last-modified pom-info]}
        (get-jar-info-for-url rc-url)]

    (-> {:resource-id [::resource name]
         :resource-name name
         :cache-key [checksum]
         :last-modified last-modified
         :url rc-url
         :from-jar true}
        (cond->
          pom-info
          (assoc :pom-info pom-info)))))

(defn find-jar-resources* [cp ^File file checksum]
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

            (if (or (.isDirectory jar-entry)
                    (not (util/is-js-file? name))
                    (should-ignore-resource? cp name))
              (recur result)
              (let [url (URL. (str "jar:file:" jar-path "!/" name))]
                (-> result
                    (conj! (-> {:resource-id [::resource name]
                                :resource-name (rc/normalize-name name)
                                :cache-key [checksum]
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
      [])

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

  (inspect-resources cp {:resources root-contents}))

;; if a jar contains a cljs.core file (compiled or source) filter out all other files
;; from that directory as it is compiled output that shouldn't be in the jar in the first place

;; cljs-bean contains an actual legit
;;   cljs-bean.from.cljs.core
;; ns that needs to be accounted for and not quarantined
;; will be filtered at a later stage if the ns does not match the filename
(defn quarantine-bad-jar-contents [^File jar-file banned-name resources]
  (let [bad
        (->> resources
             (filter (fn [{:keys [resource-name] :as rc}]
                       (str/ends-with? resource-name banned-name))))]

    (if-not (seq bad)
      resources
      ;; .jar file contains a compiled version of cljs.core
      ;; filter out all sources in that directory, likely contains many other compiled
      ;; and uncompiled sources
      (reduce
        (fn [resources {:keys [resource-name] :as bad-rc}]
          (let [bad-prefix
                (subs resource-name 0 (str/index-of resource-name banned-name))

                filtered
                (->> resources
                     (remove (fn [{:keys [resource-name] :as rc}]
                               (str/starts-with? resource-name bad-prefix)))
                     (vec))]

            (log/warn ::bad-jar-contents
              {:jar-file (.getAbsolutePath jar-file)
               :bad-prefix bad-prefix
               :bad-count (- (count resources) (count filtered))})

            filtered))
        resources
        bad))))

(defn find-jar-resources
  [{:keys [manifest-cache-dir] :as cp} ^File jar-file]
  (let [checksum
        (data/sha1-file jar-file)

        manifest-name
        (str (.getName jar-file) "." checksum ".manifest")

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
            (catch Throwable e
              (log/info-ex e ::jar-cache-read-ex {:file mfile})
              nil)))

        (let [jar-contents
              (->> (find-jar-resources* cp jar-file checksum)
                   (quarantine-bad-jar-contents jar-file "/cljs/core.js")
                   (quarantine-bad-jar-contents jar-file "/goog/base.js")
                   (process-root-contents cp jar-file))]
          (io/make-parents mfile)
          (try
            (cache/write-file mfile (assoc jar-contents ::CACHE-TIMESTAMP CACHE-TIMESTAMP))
            (catch Throwable e
              (log/info-ex e ::jar-cache-write-ex {:file mfile})))
          jar-contents))))


(defn is-gitlib-file? [^File file]
  (loop [file (.getAbsoluteFile file)]
    (cond
      (nil? file)
      false

      (and (.exists file) (.isDirectory file) (= ".gitlibs" (.getName file)))
      true

      :else
      (recur (.getParentFile file)))))

(def ^Path project-root-path
  (-> (io/file ".")
      (.getCanonicalFile)
      (.toPath)))

;; can fail to relativize when on different disks, see https://github.com/thheller/shadow-cljs/issues/966
;; the only purpose for this path currently is the build report in a relative manner so it doesn't show
;; machine specific details
;; eg. ../shadow-experiments/src/main
;; instead of C:/Users/thheller/code/shadow-experiments/src/main
;; if that fails we show the entire path regardless with a special case for gitlibs
(defn project-rel-path [^File dir]
  (if-not (is-gitlib-file? dir)
    (try
      (-> (.relativize project-root-path
            (-> dir (.getAbsoluteFile) (.toPath)))
          (str)
          (rc/normalize-name))
      (catch Exception e
        (.getAbsolutePath dir)))

    ;; gitlib files
    (let [^File gitlib-root
          (loop [curr dir]
            (if (= ".gitlibs" (.getName curr))
              (io/file curr "libs")
              (recur (.getParentFile curr))))

          dir-abs
          (.getAbsolutePath dir)

          root-abs
          (.getAbsolutePath gitlib-root)]

      ;; C:\Users\thheller\.gitlibs\libs\re-frame\re-frame\8cf68c30722a4c6f8f948a134c900d7a656ecad4\src
      ;; gitlibs://re-frame/re-frame/8cf68c30722a4c6f8f948a134c900d7a656ecad4/src
      (str "gitlibs:/" (rc/normalize-name (subs dir-abs (count root-abs)))))))

(defn make-fs-resource
  ([^File file name]
   (let [fs-root
         (loop [root file
                i (count (str/split name #"/"))]
           (if (zero? i)
             (project-rel-path root)
             (recur (.getParentFile root) (dec i))))]

     (make-fs-resource file name fs-root)))

  ([^File file name fs-root]
   (let [last-mod
         (if (.exists file)
           (.lastModified file)
           (System/currentTimeMillis))

         checksum
         (data/sha1-file file)]

     (-> {:resource-id [::resource name]
          :resource-name name
          :cache-key [checksum]
          :last-modified last-mod
          :file file
          :fs-root fs-root
          :url (.toURL file)}
         (cond->
           (is-gitlib-file? file)
           (assoc :from-jar true))))))

(comment
  (make-fs-resource
    (io/file "src" "main" "shadow" "build.clj")
    "shadow/build.cljs"))

(comment
  (is-gitlib-file? (io/file "src" "main" "shadow" "build.clj"))
  (is-gitlib-file? (io/file (System/getProperty "user.home") ".gitlibs" "libs")))

(defn find-fs-resources*
  [cp ^File root]
  (let [root-path (.getCanonicalPath root)
        rel-path (project-rel-path root)
        root-len (inc (count root-path))
        is-gitlib-root? (is-gitlib-file? root)]
    (into []
      (for [^File file (file-seq root)
            :when (and (.isFile file)
                       (not (.isHidden file))
                       (util/is-js-file? (.getName file)))
            :let [file (.getAbsoluteFile file)
                  abs-path (.getAbsolutePath file)
                  _ (assert (str/starts-with? abs-path root-path))
                  name (-> abs-path
                           (.substring root-len)
                           (rc/normalize-name))]
            :when (not (should-ignore-resource? cp name))]

        (-> (make-fs-resource file name rel-path)
            ;; treat gitlibs as if they were from a .jar
            ;; affects hot-reload and warnings logic
            (cond->
              is-gitlib-root?
              (assoc :from-jar true)))
        ))))

(defn find-fs-resources [cp ^File root]
  (->> (find-fs-resources* cp root)
       (process-root-contents cp root)))

(defn find-resources [cp ^File file]
  (cond
    (not (.exists file))
    {}

    (.isDirectory file)
    (find-fs-resources cp file)

    (and (.isFile file) (util/is-jar? (.getName file)))
    (find-jar-resources cp file)

    ;; silently ignore all other cases since we can't do anything with them anyways
    ;; don't throw since java doesn't throw either
    :else
    {}))

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

(defn index-path-merge [state source-path {:keys [resources] :as dir-contents}]
  (-> state
      (update :source-paths conj source-path)
      (util/reduce-> index-rc-merge resources)))

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

(comment
  (let [svc (start (io/file ".shadow-cljs" "jar-manifests"))]
    (index-classpath svc)

    (tap> svc)
    (stop svc)))

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

        ignore-patterns
        #{#"node_modules/"
          ;; temp files created by emacs are in the same directory
          ;; named demo/.#foo.cljs are hidden and ignored on osx/linux
          ;; but not hidden on windows so need to filter them
          #"\.#"
          ;; cljs.core aot
          #"\.aot\.js$"
          ;; closure library test files
          #"^goog/demos/"
          #"^goog/(.+)_test\.js$"
          #"goog/transpile\.js"
          ;; closure compiler support and test files
          #"^com/google/javascript"
          #"^jdk/nashorn/*"
          ;; ignore shipped builds (UI, babel-worker, etc)
          #"^shadow/.+/dist"
          ;; just in case the :output-dir of a dev build is on the classpath
          #"^public/"
          #"cljs-runtime/"}

        index
        {:ignore-patterns
         ignore-patterns

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
     :index-ref (atom index)
     ;; FIXME: ugly duplication because of should-ignore-resource? being used as an api method
     ;; this is also kept in the index-ref but having outside namespaces deref the index-ref
     ;; is uglier than duplicating this little bit
     :ignore-patterns ignore-patterns}))

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

(defn find-resource-by-name
  "returns nil if name is not on the classpath (or was filtered)"
  [{:keys [index-ref] :as cp} name]
  {:pre [(service? cp)
         (string? name)]}
  (when-let [rc-url (io/resource name)]
    (case (.getProtocol rc-url)
      "file"
      (->> (make-fs-resource (-> rc-url (.toURI) (io/file)) name)
           (inspect-resource cp)
           (set-output-name))
      "jar"
      (->> (make-jar-resource rc-url name)
           (inspect-resource cp)
           (set-output-name))

      (throw (ex-info "unexpected resource url protocol" {:name name :rc-url rc-url}))
      )))

(defn find-resource-for-provide
  [{:keys [index-ref] :as cp} provide-sym]
  {:pre [(service? cp)
         (symbol? provide-sym)]}
  (let [base-name (util/ns->path provide-sym)]
    (or (find-resource-by-name cp (str base-name ".cljs"))
        (find-resource-by-name cp (str base-name ".cljc"))
        (let [index @index-ref]
          (or (when-let [src-name (get-in index [:provide->source provide-sym])]
                (or (get-in index [:sources src-name])
                    (throw (ex-info "missing classpath source" {:src-name src-name :sym provide-sym}))))
              )))))

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
  (throw (ex-info "TBD, classpath indexing is gone." {}))
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



(defn get-all-resources
  [{:keys [index-ref] :as cp}]
  (throw (ex-info "TBD, classpath indexing is gone." {}))
  (->> (:sources @index-ref)
       (vals)))

(defn find-cljs-namespaces-in-files
  "searches classpath for clj(s|c) files (not in jars), returns expected namespaces"
  [cp dirs]
  (for [^File cp-entry (or dirs (get-classpath-entries cp))
        :when (and (.isDirectory cp-entry)
                   (not (is-gitlib-file? cp-entry)))
        :let [root-path (.toPath cp-entry)]
        ^File file (file-seq cp-entry)
        :when (and (not (.isHidden file))
                   (util/is-cljs-file? (.getName file)))
        :let [file-path (.toPath file)
              resource-name (-> (.relativize root-path file-path)
                                (.toString)
                                (rc/normalize-name))]
        :when (not (should-ignore-resource? cp resource-name))]
    (util/filename->ns resource-name)))

(defn find-resources-using-ns
  [cp ns-sym]
  {:pre [(symbol? ns-sym)]}
  (->> (find-cljs-namespaces-in-files cp (get-classpath-entries cp))
       (map #(find-resource-for-provide cp %))
       (filter (fn [{:keys [ns requires] :as rc}]
                 (contains? requires ns-sym)))
       (map :ns)
       (into #{})))

(comment
  (find-resources-using-ns
    (:classpath @shadow.cljs.devtools.server.runtime/instance-ref)
    'cljs.pprint))

(defn has-resource? [classpath ns]
  (let [resource-name (util/ns->path ns)]
    (or (io/resource (str resource-name ".cljs"))
        (io/resource (str resource-name ".cljc")))))

(comment
  (let [cp (start (io/file "tmp"))]
    (find-cljs-namespaces-in-files cp)))

(defn resolve-rel-path [^String resource-name ^String require]
  (let [parent (-> (data/as-path resource-name) (.getParent))

        ^Path path
        (cond
          (not (nil? parent))
          (.resolve parent require)

          (str/starts-with? require "./")
          (data/as-path (subs require 2))

          (str/starts-with? require "../")
          (throw (ex-info
                   (str "Cannot access \"" require "\" from \"" resource-name "\".\n"
                        "Access outside the classpath is not allowed for relative requires.")
                   {:tag ::access-outside-classpath
                    :require-from resource-name
                    :require require}))

          :else
          (data/as-path require))]

    (-> path
        (.normalize)
        (.toString)
        (rc/normalize-name))))

(defn find-js-resource
  ;; absolute require "/some/foo/bar.js" or "/some/foo/bar"
  ([cp ^String require]
   (let [require (cond-> require (str/starts-with? require "/") (subs 1))]
     (or (find-resource-by-name cp require)
         ;; FIXME: should really enforce using the full filename, don't repeat the mistakes node made ...
         ;; should warn for a while first though, don't want to break too many builds
         (find-resource-by-name cp (str require ".js")))))

  ;; relative require "./foo.js" from another rc
  ([cp {:keys [resource-name] :as require-from} ^String require]
   (when-not require-from
     (throw (ex-info "relative requires only allowed in files" {:require require})))

   (let [path (resolve-rel-path resource-name require)]
     (when (str/starts-with? path ".")
       (throw (ex-info
                (str "Cannot access \"" require "\" from \"" resource-name "\".\n"
                     "Access outside the classpath is not allowed for relative requires.")
                {:tag ::access-outside-classpath
                 :require-from resource-name
                 :require require})))

     (find-js-resource cp path)
     )))

;; taken from cljs.closure since its private there ...
(defn load-data-reader-file [mappings ^java.net.URL url]
  (let [rdr (readers/indexing-push-back-reader (readers/string-reader (slurp url)))
        new-mappings (reader/read {:eof nil :read-cond :allow} rdr)]
    (when (not (map? new-mappings))
      (throw (ex-info (str "Not a valid data-reader map")
               {:url url
                :clojure.error/phase :compilation})))
    (reduce
      (fn [m [k v]]
        (when (not (symbol? k))
          (throw (ex-info (str "Invalid form in data-reader file")
                   {:url url
                    :form k
                    :clojure.error/phase :compilation})))
        (when (and (contains? mappings k)
                   (not= (mappings k) v))
          (throw (ex-info "Conflicting data-reader mapping"
                   {:url url
                    :conflict k
                    :mappings m
                    :clojure.error/phase :compilation})))
        (assoc m k v))
      mappings
      new-mappings)))

(comment
  ;; FIXME: implement correctly

  (resolve-rel-path "foo.cljs" "../bar.js")
  (resolve-rel-path "foo.cljs" "./../bar.js")

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



