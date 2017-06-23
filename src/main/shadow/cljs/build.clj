(ns shadow.cljs.build
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.classpath :as cp]
            [cljs.analyzer :as ana]
            [cljs.compiler :as comp]
            [cljs.source-map :as sm]
            [cljs.env :as env]
            [cljs.tagged-literals :as tags]
            [clojure.tools.reader :as reader]
            [clojure.tools.reader.reader-types :as readers]
            [cognitect.transit :as transit]
            [shadow.spec :as ss]
            [shadow.cljs.util :as util]
            [shadow.cljs.log :as log]
            [shadow.cljs.cljs-specs :as cljs-specs]
            [shadow.cljs.closure :as closure]
            [shadow.cljs.cache :as cache]
            [shadow.cljs.ns-form :as ns-form]
            [clojure.spec.alpha :as s]
            [shadow.cljs.output :as output]
            [shadow.cljs.warnings :as warnings])
  (:import [java.io File FileOutputStream FileInputStream StringReader PushbackReader ByteArrayOutputStream BufferedReader ByteArrayInputStream]
           [java.net URL]
           (java.util.jar JarFile JarEntry)
           (com.google.javascript.jscomp.deps JsFileParser)
           [java.util.concurrent Executors Future ExecutorService]
           (java.security MessageDigest)
           (javax.xml.bind DatatypeConverter)
           (cljs.tagged_literals JSValue)
           (java.util.zip ZipException)
           (clojure.lang IDeref ExceptionInfo)))

(defn compiler-state? [x]
  (util/compiler-state? x))

;; (set! *warn-on-reflection* true)
(defonce stdout-lock (Object.))

(def SHADOW-TIMESTAMP
  (-> (io/resource "shadow/cljs/build.clj")
      (.openConnection)
      (.getLastModified)))

(def ^:dynamic *cljs-warnings-ref* nil)

(s/def ::resource
  (ss/map-spec
    :req
    {:input #(instance? IDeref %)
     :type #{:js :cljs :foreign}
     :name string?
     :js-name string?
     :provides (s/coll-of simple-symbol? :kind set?)
     :requires (s/coll-of simple-symbol? :kind set?)
     :require-order (s/coll-of simple-symbol? :kind vector?)
     :last-modified number?}))

(defn valid-resource? [src]
  #_(and (contains? #{:js :cljs :foreign} type)
         (instance? clojure.lang.IDeref input)
         (string? name)
         (set? provides)
         (set? requires)
         (vector? require-order)
         (number? last-modified))

  (s/valid? ::resource src))

(defn usable-resource? [{:keys [type provides requires] :as rc}]
  (or (seq provides) ;; provides something is useful
      (seq requires) ;; requires something is less useful?
      ))

(defn is-jar? [^String name]
  (.endsWith (str/lower-case name) ".jar"))

(defn is-cljs-file? [^String name]
  (or (.endsWith (str/lower-case name) ".cljs")
      (.endsWith (str/lower-case name) ".cljc")))

(defn is-cljc? [^String name]
  (.endsWith name ".cljc"))

(defn is-cljs? [^String name]
  (.endsWith name ".cljs"))

(defn is-js-file? [^String name]
  (.endsWith (str/lower-case name) ".js"))

(defn is-cljs-resource? [^String name]
  (or (is-cljs-file? name)
      (is-js-file? name)
      ))

(defn cljs->js-name [name]
  (str/replace name #"\.cljs$" ".js"))

(defn ns->path [ns]
  (-> ns
      (str)
      (str/replace #"\." "/")
      (str/replace #"-" "_")))

(defn ns->cljs-file [ns]
  (-> ns
      (ns->path)
      (str ".cljs")))

(defn filename->ns [^String name]
  {:pre [(or (.endsWith name ".js")
             (.endsWith name ".clj")
             (.endsWith name ".cljs")
             (.endsWith name ".cljc"))]}
  (-> name
      (str/replace #"\.(js|clj(s|c))$" "")
      (str/replace #"_" "-")
      (str/replace #"[/\\]" ".")
      (symbol)))

(defn conj-in [m k v]
  (update-in m k (fn [old] (conj old v))))

(defn set-conj [x y]
  (if x
    (conj x y)
    #{y}))

(defn vec-conj [x y]
  (if x
    (conj x y)
    [y]))

(defn inspect-js-resource [state {:keys [name input] :as rc}]
  {:pre [(util/compiler-state? state)]}
  (let [deps (-> (doto (JsFileParser. (.getErrorManager (::cc state)))
                   (.setIncludeGoogBase true))
                 (.parseFile name name @input))

        module-type
        (-> deps (.getLoadFlags) (.get "module"))

        ;; FIXME: bug in Closure that es6 files do not depend on goog?
        ;; doesn't hurt that it is missing though
        require-order
        (into [] (map closure/munge-goog-ns) (.getRequires deps))

        provides
        (into #{} (map closure/munge-goog-ns) (.getProvides deps))]

    (-> rc
        (assoc
          ;; :dependency-info deps ;; FIXME: only add when trying to use it in ModuleLoader, breaks caching
          :require-order require-order
          :requires (into #{} require-order)
          :provides provides)
        (cond->
          (seq module-type)
          (merge (let [ns (filename->ns name)]
                   (assert (<= 0 (count provides) 1) "module with more than one provide?")
                   ;; FIXME: cannot do this aliasing, it will break things
                   ;; source-path-a/lib/foo.js -> lib.foo
                   ;; source-path-b/lib/foo.js -> lib.foo
                   {:module-type (keyword module-type)
                    :module-alias (first provides)
                    ;; :ns ns
                    :provides provides ;; (conj provides ns)
                    :js-name (util/flat-filename name)}))
          ))))

(defn macros-from-ns-ast [state {:keys [require-macros use-macros]}]
  {:pre [(util/compiler-state? state)]}
  (into #{} (concat (vals require-macros) (vals use-macros))))

(defn rewrite-ns-aliases
  [{:keys [requires uses require-order] :as ast}
   {:keys [ns-alias-fn] :as state}]
  (let [should-rewrite
        (->> require-order
             (filter #(ns-alias-fn state %))
             (into #{}))]

    (if-not (seq should-rewrite)
      ast
      (let [rewrite-ns
            (fn [ns]
              (if (contains? should-rewrite ns)
                (ns-alias-fn state ns)
                ns))

            rewrite-ns-map
            (fn [ns-map alias-self?]
              (reduce-kv
                (fn [ns-map alias ns]
                  (if-not (contains? should-rewrite ns)
                    ns-map
                    (let [target (ns-alias-fn state ns)]
                      (-> ns-map
                          (assoc alias target)
                          (cond->
                            alias-self?
                            (assoc ns target))))))
                ns-map
                ns-map))]

        (assoc ast
          :require-order
          (into [] (map rewrite-ns) require-order)
          :requires
          (rewrite-ns-map requires true)
          :uses
          (rewrite-ns-map uses false))
        ))))

(defn update-rc-from-ns
  [state rc {:keys [name require-order js-requires] :as ast}]
  {:pre [(util/compiler-state? state)]}
  (assoc rc
    :ns name
    :ns-info (dissoc ast :env)
    :provides #{name}
    :macro-namespaces (macros-from-ns-ast state ast)
    :requires (into #{} require-order)
    :js-requires js-requires
    :require-order require-order))

(defn js-resolver-for-file [state file]
  (ns-form/resolve-relative-to-output-dir
    (:output-dir state)
    file))

(defn js-resolver-for-resource [state name]
  {:pre [(string? name)]}
  (js-resolver-for-file
    state
    (get-in state [:sources name :file])))

(defn peek-into-cljs-resource
  "looks at the first form in a .cljs file, analyzes it if (ns ...) and returns the updated resource
   with ns-related infos"
  [state {:keys [^String name input] :as rc}]
  {:pre [(util/compiler-state? state)]}
  (let [eof-sentinel (Object.)
        cljc? (is-cljc? name)
        opts (merge
               {:eof eof-sentinel}
               (when cljc?
                 {:read-cond :allow :features #{:cljs}}))
        rdr (StringReader. @input)
        in (readers/indexing-push-back-reader (PushbackReader. rdr) 1 name)]
    (binding [reader/*data-readers* tags/*cljs-data-readers*]
      (try
        (let [peek (reader/read opts in)]
          (if (identical? peek eof-sentinel)
            (throw (ex-info "file is empty" {:name name}))
            (let [js-resolve
                  (js-resolver-for-file state (:file rc))

                  ast (-> (ns-form/parse peek)
                          (ns-form/rewrite-js-requires js-resolve)
                          (rewrite-ns-aliases state))]
              (-> state
                  (update-rc-from-ns rc ast)
                  (assoc :cljc cljc?)))))
        (catch Exception e
          ;; could not parse NS
          ;; be silent about it until we actually require and attempt to compile the file
          ;; make best estimate guess what the file might provide based on name
          (let [guessed-ns (filename->ns name)]
            (assoc rc
              :ns guessed-ns
              :requires #{'cljs.core}
              :require-order ['cljs.core]
              :provides #{guessed-ns}
              :type :cljs
              )))))))

(defn inspect-resource
  [state {:keys [url name] :as rc}]
  {:pre [(util/compiler-state? state)]}
  (cond
    (is-js-file? name)
    (->> (assoc rc :type :js :js-name (util/flat-filename name))
         (inspect-js-resource state))

    (is-cljs-file? name)
    (let [rc (assoc rc :type :cljs :js-name (util/flat-filename (str/replace name #"\.clj(s|c)$" ".js")))]
      (if (= name "deps.cljs")
        rc
        (peek-into-cljs-resource state rc)))

    :else
    (throw (ex-info "cannot identify as cljs resource" {:name name :url (str url)}))))

(def ^{:doc "windows filenames need to be normalized because they contain backslashes which browsers don't understand"}
normalize-resource-name
  (if (= File/separatorChar \/)
    identity
    (fn [^String name]
      (str/replace name File/separatorChar \/))))

(defn should-ignore-resource?
  [{:keys [ignore-patterns] :as state} name]
  (loop [patterns ignore-patterns]
    (if-let [pattern (first patterns)]
      (if (re-find pattern name)
        true
        (recur (rest patterns)))
      false
      )))

(defn create-jar-manifest
  "returns a map of {source-name resource-info}"
  [state path]
  {:pre [(util/compiler-state? state)]}
  (let [file (io/file path)
        abs-path (.getCanonicalPath file)]

    (try
      (let [
            jar-file (JarFile. file)
            last-modified (.lastModified file)
            entries (.entries jar-file)
            slurp-entry (fn [entry]
                          (with-open [in (.getInputStream jar-file entry)]
                            (slurp in)))]
        (loop [result (transient {})]
          (if (not (.hasMoreElements entries))
            (persistent! result)
            (let [^JarEntry jar-entry (.nextElement entries)
                  name (.getName jar-entry)]
              (if (or (not (is-cljs-resource? name))
                      (should-ignore-resource? state name))
                (recur result)
                (let [url (URL. (str "jar:file:" abs-path "!/" name))
                      rc (inspect-resource state
                           {:name (normalize-resource-name name)
                            :from-jar true
                            :source-path path
                            :last-modified last-modified
                            :url url
                            :input (atom (slurp-entry jar-entry))})]
                  (recur (assoc! result name rc))
                  ))))))
      (catch ZipException e
        (util/log state {:type :bad-jar :path abs-path})
        ;; just pretend its empty
        {})
      (catch Exception e
        (throw (ex-info (str "failed to generate jar manifest for file: " abs-path) {:abs-path abs-path} e)))
      )))

(defn write-jar-manifest [file manifest]
  (let [data (->> (vals manifest)
                  ;; :input is non serializable deref, don't want to store actual content
                  ;; might not need it, just a performance issue
                  ;; reading closure jar with js contents 300ms without content 5ms
                  ;; since we are only using a small percentage of those file we delay reading
                  (map #(dissoc % :input))
                  (into []))]
    (cache/write-cache file data)
    ))

(defn read-jar-manifest [file]
  (let [entries (cache/read-cache file)]
    (reduce
      (fn [m {:keys [name url] :as v}]
        (assoc m name (assoc v :input (delay (slurp url)))))
      {}
      entries)))

(defn process-deps-cljs
  "manifest is a {source-name source-info} map, transform that into
   {:resources [source-info] :externs [orphan-externs]} using deps.cljs if present"
  [{:keys [use-file-min] :as state} manifest source-path]
  {:pre [(util/compiler-state? state)
         (map? manifest)]}
  (let [{:keys [url] :as deps}
        (get manifest "deps.cljs")]
    (if (nil? deps)
      {:resources (vals manifest)}
      (let [{:keys [externs foreign-libs] :as deps-cljs}
            (-> @(:input deps) (edn/read-string))

            _ (when-not (s/valid? ::cljs-specs/deps-cljs deps-cljs)
                (throw (ex-info "invalid deps.cljs"
                         (assoc (s/explain-data ::cljs-specs/deps-cljs deps-cljs)
                           :tag ::deps-cljs
                           :url url))))
            manifest
            (dissoc manifest "deps.cljs")

            get-externs-source
            (fn [manifest ext-name]
              (or (when-some [input (get-in manifest [ext-name :input])]
                    @input)
                  (throw (ex-info "deps.cljs error, :externs file not in sources"
                           {:tag ::deps-cljs :url url :ext-name ext-name}))))

            ;; a foreign lib groups several files into one actual resource
            #_{:externs ["foo.ext.js" "bar.ext.js"]
               :file-min "foo.min.js"
               :file "foo.js"
               :provides ["foo" "bar"]
               :requires ["baz"]}

            ;; this is extracted to actual shadow-build resources
            ;; the foreign-lib "include" files "foo.ext.js" "bar.ext.js" ...
            ;; are removed as they are now included in this rc
            #_{:type :foreign
               :name "foo.js" ;; or foo.min.js
               :externs [...]
               :externs-source "concatenated externs source so these are not loaded from cp"
               :provides #{as-symbols}
               :requires #{as-symbols}}

            manifest
            (reduce
              (fn [manifest {:keys [externs provides requires] :as foreign-lib}]
                (when-not (seq provides)
                  (throw (ex-info "deps.cljs foreign-lib without provides"
                           {:tag ::deps-cljs :url url :foreign-lib foreign-lib})))

                (let [[lib-key lib-other]
                      (cond
                        (and use-file-min (contains? foreign-lib :file-min))
                        [:file-min :file]
                        (:file foreign-lib)
                        [:file :file-min])

                      lib-name
                      (get foreign-lib lib-key)

                      rc
                      (get manifest lib-name)]

                  (when (nil? rc)
                    (throw (ex-info "deps.cljs refers to file not in jar"
                             {:tag ::deps-cljs :url url :foreign-lib foreign-lib})))

                  (let [dissoc-all
                        (fn [m list]
                          (apply dissoc m list))

                        require-order
                        (into [] (map symbol) requires)

                        externs-source
                        (->> externs
                             (map #(get-externs-source manifest %))
                             (str/join "\n"))

                        ;; mark rc as foreign and merge with externs instead of leaving externs as seperate rc
                        rc
                        (assoc rc
                          :type :foreign
                          :requires (set require-order)
                          :require-order require-order
                          :provides (set (map symbol provides))
                          :externs externs
                          :externs-source externs-source)]

                    (-> manifest
                        (dissoc-all externs)
                        ;; remove :file or :file-min
                        (dissoc (get foreign-lib lib-other))
                        (assoc lib-name rc)))))
              manifest
              foreign-libs)

            ;; grab externs source from manifest and export
            ;; this is so we don't use io/resource to load the externs later
            ;; some CLJS libs with externs.js at the root will otherwise overwrite each other
            externs
            (->> externs
                 (map (fn [ext-name]
                        {:name ext-name
                         :source (get-externs-source manifest ext-name)}))
                 (into []))

            ;; remove them from resource listing
            manifest
            (apply dissoc manifest externs)]

        {:resources (vals manifest)
         :externs externs}
        ))))

(defonce JAR-LOCK (Object.))

(defn find-jar-resources
  [{:keys [manifest-cache-dir cache-level] :as state} path]
  {:pre [(util/compiler-state? state)]}
  ;; FIXME: assuming a jar with the same name and same last modified is always identical, probably not. should md5 the full path?

  ;; locking to avoice race-condition in cache where two threads might be read/writing jar manifests at the same time
  ;; A is writing
  ;; B sees that file exists and starts reading although A is not finished yet
  ;; boom strange EOF errors
  ;; could instead write to temp file and move and but that basically just causes manifests to be generated twice
  (locking JAR-LOCK
    (let [manifest-name
          (let [jar (io/file path)]
            (str (.lastModified jar) "-" (.getName jar) ".manifest"))

          mfile
          (io/file manifest-cache-dir manifest-name)

          jar-file
          (io/file path)

          manifest
          (when (and (.exists mfile)
                     (>= (.lastModified mfile) (.lastModified jar-file))
                     (>= (.lastModified mfile) SHADOW-TIMESTAMP))
            (try
              (read-jar-manifest mfile)
              (catch Exception e
                (util/log state {:type :manifest-error :file mfile :e e})
                nil)))

          manifest
          (or manifest
              (let [manifest (create-jar-manifest state path)]
                (io/make-parents mfile)
                (write-jar-manifest mfile manifest)
                manifest))]

      (process-deps-cljs state manifest path))))

(defn make-fs-resource [state source-path rc-name ^File rc-file]
  (inspect-resource
    state
    {:name rc-name
     :file rc-file
     :source-path source-path
     :last-modified (.lastModified rc-file)
     :url (.toURL (.toURI rc-file))
     :input (delay (slurp rc-file))}))

(defn find-fs-resources
  [state ^String path]
  {:pre [(util/compiler-state? state)
         (seq path)]}
  (let [root (io/file path)
        root-path (.getCanonicalPath root)
        root-len (inc (count root-path))

        manifest
        (->> (for [^File file (file-seq root)
                   :let [file (.getCanonicalFile file)
                         abs-path (.getCanonicalPath file)]
                   :when (and (is-cljs-resource? abs-path)
                              (not (.isHidden file)))
                   :let [name (-> abs-path
                                  (.substring root-len)
                                  (normalize-resource-name))]
                   :when (not (should-ignore-resource? state name))]
               (make-fs-resource state root-path name file))
             (map (juxt :name identity))
             (into {}))]

    (process-deps-cljs state manifest root-path)))

(defn get-resource-for-provide [state ns-sym]
  {:pre [(util/compiler-state? state)
         (symbol? ns-sym)]}
  (when-let [name (get-in state [:provide->source ns-sym])]
    (get-in state [:sources name])))

(defn find-resource-by-js-name [state js-name]
  {:pre [(util/compiler-state? state)
         (string? js-name)]}
  (let [rcs
        (->> (:sources state)
             (vals)
             (filter #(= js-name (:js-name %)))
             (into []))]
    (when (not= 1 (count rcs))
      ;; FIXME: this should be checked when scanning for resources
      (throw (ex-info (format "multiple resources for js-name:%s" js-name)
               {:js-name js-name
                :resources rcs})))
    (first rcs)))

(defn- get-deps-for-src* [{:keys [deps-stack] :as state} name]
  {:pre [(util/compiler-state? state)]}
  (when-not (string? name)
    (throw (ex-info (format "trying to get deps for \"%s\"" (pr-str name)) {})))

  (cond
    ;; don't run in circles
    (some #(= name %) deps-stack)
    (let [path (->> (conj deps-stack name)
                    (drop-while #(not= name %))
                    (str/join " -> "))]
      (throw (ex-info (format "circular dependency: %s" path) {:name name :stack deps-stack})))

    ;; don't revisit
    (contains? (:deps-visited state) name)
    state

    :else
    (let [src (get-in state [:sources name])]
      (when-not src
        (throw (ex-info (format "cannot find resource \"%s\"" name) {:name name})))

      (let [requires (:require-order src)]
        (when-not (and requires (vector? requires))
          (throw (ex-info (format "cannot find required deps for \"%s\"" name) {:name name})))

        (let [state (-> state
                        (conj-in [:deps-visited] name)
                        (conj-in [:deps-stack] name))
              state (->> requires
                         (map (fn [require-sym]
                                (let [src-name (get-in state [:provide->source require-sym])]
                                  (when-not src-name
                                    (throw
                                      (ex-info
                                        (format "The required \"%s\" is not available, required by \"%s\"" require-sym name)
                                        {:tag ::missing-ns
                                         :ns require-sym
                                         :src name})))
                                  src-name
                                  )))
                         ;; remove base as it will always be provided
                         (remove #{"goog/base.js"})
                         ;; forcing for less confusing stack trace
                         (into [] (distinct))
                         (reduce get-deps-for-src* state))
              state (update state :deps-stack (fn [stack] (into [] (butlast stack))))]
          (conj-in state [:deps-ordered] name)
          )))))

(defn get-deps-for-src
  "returns names of all required sources for a given resource by name (in dependency order), does include self
   (eg. [\"goog/string/string.js\" \"cljs/core.cljs\" \"my-ns.cljs\"])"
  [state src-name]
  {:pre [(util/compiler-state? state)
         (string? src-name)]}
  (-> state
      (assoc :deps-stack []
        :deps-ordered []
        :deps-visited #{})
      (get-deps-for-src* src-name)
      :deps-ordered))

(defn get-deps-for-ns
  "returns names of all required sources for a given ns (in dependency order), does include self
   (eg. [\"goog/string/string.js\" \"cljs/core.cljs\" \"my-ns.cljs\"])"
  [state ns-sym]
  {:pre [(util/compiler-state? state)
         (symbol? ns-sym)]}
  (let [name (get-in state [:provide->source ns-sym])]
    (when-not name
      (let [reqs (->> state
                      :sources
                      (vals)
                      (filter #(contains? (:requires %) ns-sym))
                      (map :name)
                      (into #{}))]
        (throw (ex-info (format "ns \"%s\" not available, required by %s" ns-sym reqs) {:tag ::missing-ns :ns ns-sym :required-by reqs}))))

    (get-deps-for-src state name)
    ))


(defn post-analyze-ns [{:keys [name] :as ast} compiler-state merge?]
  (let [ast
        (-> ast
            (util/load-macros)
            (util/infer-macro-require)
            (util/infer-macro-use)
            (util/infer-renames-for-macros))]

    (util/check-uses! ast)
    (util/check-renames! ast)

    (let [ana-info
          (dissoc ast :env :op :form)]
      ;; FIXME: nukes all defs when not merge?
      ;; this is so ^:const doesn't fail when re-compiling
      ;; but if a REPL is connected this may nuke a REPL def
      ;; ns from the REPL will merge but autobuild will not, should be ok though
      (if merge?
        (swap! env/*compiler* update-in [::ana/namespaces name] merge ana-info)
        (swap! env/*compiler* assoc-in [::ana/namespaces name] ana-info)))

    ;; FIXME: is this the correct location to do this?
    ;; FIXME: using alter instead of reset, to avoid completely removing meta
    ;; when thing/ns.clj and thing/ns.cljs both have different meta

    (when-let [the-ns (find-ns name)]
      (.alterMeta ^clojure.lang.Namespace the-ns merge (seq [(meta name)])))

    ast))

(defn post-analyze [{:keys [op] :as ast} compiler-state]
  (case op
    :ns
    (post-analyze-ns ast compiler-state false)
    :ns*
    (throw (ex-info "ns* not supported (require, require-macros, import, import-macros, ... must be part of your ns form)" ast))
    ast))

(defn hijacked-parse-ns [env form name {::keys [compiler-state] :as opts}]
  (-> (ns-form/parse form)
      (ns-form/rewrite-js-requires (js-resolver-for-resource compiler-state name))
      (rewrite-ns-aliases compiler-state)
      (assoc :env env :form form :op :ns)))

;; I don't want to use with-redefs but I also don't want to replace the default
;; keep a reference to the default impl and dispatch based on binding
;; this ensures that out path is only taken when wanted
(defonce default-parse-ns (get-method ana/parse 'ns))

(def ^:dynamic *compiling* false)

(defmethod ana/parse 'ns
  [op env form name opts]
  (if *compiling*
    (hijacked-parse-ns env form name opts)
    (default-parse-ns op env form name opts)))

(comment
  ;; FIXME: these rely on with-redefs which isn't threadsafe

  (def default-parse ana/parse)

  (defn shadow-parse [op env form name opts]
    (condp = op
      ;; the default ana/parse 'ns has way too many side effects we don't need or want
      ;; don't want analyze-deps -> never needed
      ;; don't want require or macro ns -> post-analyze
      ;; don't want check-uses -> doesn't recognize macros
      ;; don't want check-use-macros -> doesnt handle (:require [some-ns :refer (a-macro)])
      ;; don't want swap into compiler env -> post-analyze
      'ns (hijacked-parse-ns env form name opts)
      (default-parse op env form name opts)))

  (def default-analyze-form ana/analyze-form)

  ;; FIXME: doing this so I can access the form that caused a warning
  ;; some warnings do not include it, should really provide patches to add them
  ;; but this is simpler and an experiment anyways
  (defn shadow-analyze-form
    [env form name opts]
    (let [warnings-before
          @*cljs-warnings-ref*

          result
          (default-analyze-form env form name opts)

          warnings-after
          @*cljs-warnings-ref*]

      (when-not (identical? warnings-before warnings-after)
        (swap! *cljs-warnings-ref*
          (fn [warnings]
            (->> warnings
                 (map (fn [x]
                        (if (contains? x :form)
                          x
                          (assoc x :form form))))
                 (into [])
                 ))))
      result
      )))

(defn analyze
  ([state compile-state form]
   (analyze state compile-state form :statement))
  ([state {:keys [ns name] :as compile-state} form context]
   {:pre [(map? compile-state)
          (symbol? ns)
          (string? name)
          (seq name)]}

   (binding [*ns* (create-ns ns)
             ana/*passes* (:analyzer-passes state)
             ;; [infer-type ns-side-effects] is default, we don't want the side effects
             ;; altough it is great that the side effects are now optional
             ;; the default still doesn't handle macros properly
             ;; so we keep hijacking
             ana/*cljs-ns* ns
             ana/*cljs-file* name]

     (-> (ana/empty-env) ;; this is anything but empty! requires *cljs-ns*, env/*compiler*
         (assoc :context context)
         (ana/analyze form name
           ;; doing this since I no longer want :compiler-options at the root
           ;; of the compiler state, instead they are in :compiler-options
           ;; still want the compiler-state accessible though
           (assoc (:compiler-options state)
             ::compiler-state state))
         (post-analyze state)))))

(defn do-compile-cljs-string
  [{:keys [name] :as init} reduce-fn cljs-source cljc?]
  (let [eof-sentinel (Object.)
        opts (merge
               {:eof eof-sentinel}
               (when cljc?
                 {:read-cond :allow :features #{:cljs}}))
        in (readers/indexing-push-back-reader (PushbackReader. (StringReader. cljs-source)) 1 name)]

    (binding [comp/*source-map-data*
              (atom {:source-map (sorted-map)
                     :gen-col 0
                     :gen-line 0})]

      (let [result
            (loop [{:keys [ns ns-info] :as compile-state} init]
              (let [form
                    (binding [*ns*
                              (create-ns ns)

                              ana/*cljs-ns*
                              ns

                              ana/*cljs-file*
                              name

                              reader/*data-readers*
                              tags/*cljs-data-readers*

                              reader/*alias-map*
                              (merge reader/*alias-map*
                                     (:requires ns-info)
                                     (:require-macros ns-info))]
                      (reader/read opts in))]

                (if (identical? form eof-sentinel)
                  ;; eof
                  compile-state
                  (recur (reduce-fn compile-state form)))))]

        (assoc result :source-map (:source-map @comp/*source-map-data*))
        ))))

(defn default-compile-cljs
  [state compile-state form]
  (let [ast
        (analyze state compile-state form)

        ast-js
        (with-out-str
          (comp/emit ast))

        compile-state
        (if (= :ns (:op ast))
          (update-rc-from-ns state compile-state ast)
          compile-state)]

    (-> compile-state
        (update-in [:js] str ast-js)
        (cond->
          (:retain-ast state)
          (-> (update-in [:ast] conj ast)
              (update-in [:forms] conj form))
          ))))

(defn warning-collector [build-env warnings warning-type env extra]
  ;; FIXME: currently there is no way to turn off :infer-externs
  ;; the work is always done and the warning is always generated
  ;; it is just not emitted when *warn-in-infer* is not set

  ;; we collect all warnings always since any warning should prevent caching
  ;; :infer-warnings however are very inaccurate so we filter those unless
  ;; explicitly enabled, mirroring what CLJS does more closely.
  (when (or (not= :infer-warning warning-type)
            (get ana/*cljs-warnings* :infer-warning))

    (let [{:keys [line column]}
          env

          msg
          (ana/error-message warning-type extra)]

      (swap! warnings conj
        {:warning warning-type
         :line line
         :column column
         :msg msg
         :extra extra}
        ))))

(defmacro with-warnings
  "given a body that produces a compilation result, collect all warnings and assoc into :warnings"
  [build-env & body]
  `(let [warnings#
         (atom [])

         result#
         (ana/with-warning-handlers
           [(partial warning-collector ~build-env warnings#)]
           (binding [*cljs-warnings-ref* warnings#]
             ~@body))]

     (assoc result# :warnings @warnings#)))

(defn compile-cljs-string
  [state compile-state cljs-source name cljc?]
  (with-warnings state
    (do-compile-cljs-string
      compile-state
      (partial default-compile-cljs state)
      cljs-source
      cljc?)))

(defn compile-cljs-seq
  [state compile-state cljs-forms name]
  (with-warnings state
    (reduce
      (partial default-compile-cljs state)
      compile-state
      cljs-forms)))

(defn make-runtime-setup
  [{:keys [runtime] :as state}]
  (->> [(case (:print-fn runtime)
          :console
          "cljs.core.enable_console_print_BANG_();"
          :none
          "")]
       (str/join "\n")))

(defn do-compile-cljs-resource
  "given the compiler state and a cljs resource, compile it and return the updated resource
   should not touch global state"
  [{:keys [compiler-options source-map-comment] :as state} {:keys [name js-name input] :as rc}]
  (let [{:keys [static-fns elide-asserts]}
        compiler-options]

    (binding [ana/*cljs-static-fns*
              static-fns

              ana/*file-defs*
              (atom #{})

              ;; initialize with default value
              ;; must set binding to it is thread bound, since the analyzer may set! it
              ana/*unchecked-if*
              ana/*unchecked-if*

              ;; root binding for warnings so (set! *warn-on-infer* true) can work
              ana/*cljs-warnings*
              ana/*cljs-warnings*

              *assert*
              (not= elide-asserts true)]

      (let [source @input]
        (util/with-logged-time
          [state {:type :compile-cljs :name name}]

          (let [compile-init
                {:name name :ns 'cljs.user :js "" :ast [] :forms []}

                {:keys [js ns requires require-order source-map warnings ast forms]}
                (cond
                  (string? source)
                  (compile-cljs-string state compile-init source name (is-cljc? name))
                  (vector? source)
                  (compile-cljs-seq state compile-init source name)
                  :else
                  (throw (ex-info "invalid cljs source type" {:name name :source source})))

                js
                (if (= name "cljs/core.cljs")
                  (str js "\n" (make-runtime-setup state))
                  js)]

            (when-not ns
              (throw (ex-info "cljs file did not provide a namespace" {:file name})))

            (assoc rc
              :output js
              :requires requires
              :require-order require-order
              :compiled-at (System/currentTimeMillis)
              :provides #{ns}
              :compiled true
              :warnings warnings
              :ast ast
              :forms forms
              :source-map source-map)))))))


(defn get-cache-file-for-rc
  ^File [{:keys [cache-dir] :as state} {:keys [name] :as rc}]
  (io/file cache-dir "ana" (str name ".cache.transit.json")))

(defn get-max-last-modified-for-source [state source-name]
  (let [{:keys [last-modified macro-namespaces] :as rc}
        (get-in state [:sources source-name])]

    (transduce
      (map #(get-in state [:macros % :last-modified]))
      (completing
        (fn [a b]
          (Math/max ^long a ^long b)))
      last-modified
      macro-namespaces
      )))

(defn make-age-map
  "procudes a map of {source-name last-modified} for caching to identify
   whether a cache is safe to use (if any last-modifieds to not match if is safer to recompile)"
  [state ns]
  (reduce
    (fn [age-map source-name]
      (let [last-modified (get-max-last-modified-for-source state source-name)]
        ;; zero? is a pretty ugly indicator for deps that should not affect cache
        (if (pos? last-modified)
          (assoc age-map source-name last-modified)
          age-map)))
    {:SHADOW-TIMESTAMP SHADOW-TIMESTAMP}
    (get-deps-for-ns state ns)))

(def cache-affecting-options
  [:static-fns
   :elide-asserts
   :optimize-constants
   :emit-constants
   :source-map])

(defn load-cached-cljs-resource
  [{:keys [cache-dir cljs-runtime-path] :as state}
   {:keys [ns js-name name last-modified] :as rc}]
  (let [cache-file (get-cache-file-for-rc state rc)
        cache-js (io/file cache-dir cljs-runtime-path js-name)]

    (try
      (when (and (.exists cache-file)
                 (> (.lastModified cache-file) last-modified)
                 (.exists cache-js)
                 (> (.lastModified cache-js) last-modified))

        (let [cache-data
              (cache/read-cache cache-file)

              age-of-deps
              (make-age-map state ns)]

          ;; just checking the "maximum" last-modified of all dependencies is not enough
          ;; must check times of all deps, mostly to guard against jar changes
          ;; lib-A v1 was released 3 days ago
          ;; lib-A v2 was released 1 day ago
          ;; we depend on lib-A and compile against v1 today
          ;; realize that a new version exists and update deps
          ;; compile again .. since we were compiled today the min-age is today
          ;; which is larger than v2 release date thereby using cache if only checking one timestamp

          (when (and (= (:source-path cache-data) (:source-path rc))
                     (= age-of-deps (:age-of-deps cache-data))
                     (every?
                       #(= (get-in state [:compiler-options %])
                           (get-in cache-data [:cache-options %]))
                       cache-affecting-options))
            (util/log state {:type :cache-read
                             :name name})

            ;; restore analysis data
            (let [ana-data (:analyzer cache-data)]

              (swap! env/*compiler* assoc-in [::ana/namespaces (:ns cache-data)] ana-data)
              (util/load-macros ana-data))

            ;; merge resource data & return it
            (-> (merge rc cache-data)
                (dissoc :analyzer :cache-options :age-of-deps)
                (assoc :cached true
                  :output (slurp cache-js))))))

      (catch Exception e
        (util/log state {:type :cache-error
                         :ns ns
                         :name name
                         :error e})
        nil))))

(defn write-cached-cljs-resource
  [{:keys [ns name js-name] :as rc} {:keys [cache-dir cljs-runtime-path] :as state}]

  ;; only cache files that don't have warnings!
  (when-not (seq (:warnings rc))
    (let [cache-file
          (get-cache-file-for-rc state rc)]

      (try
        (let [cache-data
              (-> rc
                  (dissoc :file :output :input :url :forms :ast)
                  (assoc :age-of-deps (make-age-map state ns)
                    :analyzer (get-in @env/*compiler* [::ana/namespaces ns])))

              cache-options
              (reduce
                (fn [cache-options option-key]
                  (assoc cache-options option-key (get-in state [:compiler-options option-key])))
                {}
                cache-affecting-options)

              cache-data
              (assoc cache-data :cache-options cache-options)

              cache-js
              (io/file cache-dir cljs-runtime-path js-name)]

          (io/make-parents cache-file)
          (cache/write-cache cache-file cache-data)

          (io/make-parents cache-js)
          (spit cache-js (:output rc))

          (util/log state {:type :cache-write
                           :ns ns
                           :name name})

          true)
        (catch Exception e
          (util/log state {:type :cache-error
                           :ns ns
                           :name name
                           :error e})
          nil)
        ))))

(defn maybe-compile-cljs
  "take current state and cljs resource to compile
   make sure you are in with-compiler-env"
  [{:keys [cache-dir cache-level] :as state} {:keys [name from-jar file url] :as src}]
  (let [cache? (and cache-dir
                    ;; even with :all only cache resources that are in jars or have a file
                    ;; cljs.user (from repl) should never be cached
                    (or (and (= cache-level :all)
                             (or from-jar file))
                        (and (= cache-level :jars)
                             from-jar)))]
    (or (when cache?
          (load-cached-cljs-resource state src))
        (let [compiled-src
              (try
                (do-compile-cljs-resource state src)
                (catch Exception e
                  (let [{:keys [tag line column] :as data}
                        (ex-data e)

                        err-data
                        (-> {:tag ::compile-cljs
                             :source-name name
                             :url url
                             :file file}
                            (cond->
                              line
                              (assoc :line line)

                              column
                              (assoc :column column)

                              (and data (= tag :cljs/analysis-error) line column)
                              (assoc :source-excerpt (warnings/get-source-excerpt state src {:line line :column column}))))]

                    (throw (ex-info (format "failed to compile resource: %s" name) err-data e)))))]

          (when cache?
            (write-cached-cljs-resource compiled-src state))

          ;; fresh compiled, not from cache
          (assoc compiled-src :cached false)))))

(defn merge-provides [state provided-by provides]
  (reduce
    (fn [state provide]
      (assoc-in state [:provide->source provide] provided-by))
    state
    provides))

(defn unmerge-provides [state provides]
  (reduce
    (fn [state provide]
      (update-in state [:provide->source] dissoc provide))
    state
    provides))

(defn unmerge-resource [state name]
  (if-let [{:keys [provides] :as current} (get-in state [:sources name])]
    (-> state
        (unmerge-provides provides)
        (update-in [:sources] dissoc name))
    ;; else: not present
    state))

(defn is-same-resource? [a b]
  (and (= (:name a) (:name b))
       (= (:source-path a) (:source-path b))
       ;; some things might not have an url, be lax about that
       (or (:url a) (:url b))
       (= (:url a) (:url b))))

(defn merge-resource
  [state {:keys [name provides url] :as src}]
  (cond
    (not (valid-resource? src))
    (do (util/log state (-> (s/explain-data ::resource src)
                            (assoc
                              :type :invalid-resource
                              :name name
                              :url url)))
        state)

    (and (= :js (:type src))
         (contains? (:provides src) 'cljs.core))
    (do (util/log state {:type :bad-resource :url url})
        state)

    ;; no not merge files that don't have the expected path for their ns
    ;; not really needed but cljs does this, so we should enforce it as well
    (and (= :cljs (:type src))
         (symbol? (:ns src))
         (let [expected-name (ns->cljs-file (:ns src))
               expected-cljc (str/replace expected-name #".cljs$" ".cljc")]
           (not (or (= name expected-name)
                    (= name expected-cljc)
                    ))))

    (do (util/log state
          {:type :name-violation
           :src src
           :expected (str (ns->cljs-file (:ns src)) " (or .cljc)")})
        ;; still want to remember the resource so it doesn't get detected as new all the time
        ;; remove all provides, otherwise it might end up being used despite the invalid name
        ;; enforce this behavior since the warning might get overlooked easily
        (let [invalid-src (assoc src
                            :provides #{}
                            :requires #{}
                            :require-order [])]
          (assoc-in state [:sources name] invalid-src)))

    ;; do not merge files that are already present from a different source path
    (when-let [existing (get-in state [:sources name])]
      (not (is-same-resource? src existing)))

    (do (when (not (and (not (get-in state [:sources name :from-jar]))
                        (:from-jar src)))
          ;; don't complain if we have a file from the fs and the conflict is from the jar
          ;; assume thats an intentional override
          (util/log state
            {:type :duplicate-resource
             :name name
             :path-use (get-in state [:sources name :source-path])
             :path-ignore (:source-path src)}))
        state)

    ;; now we need to handle conflicts for cljc/cljs files
    ;; only use cljs if both exist
    :valid-resource
    (let [cljc?
          (is-cljc? name)

          cljc-name
          (when (is-cljs? name)
            (str/replace name #"cljs$" "cljc"))

          cljs-name
          (when cljc?
            (str/replace name #"cljc$" "cljs"))

          lookup-xf
          (comp (map #(get-in state [:provide->source %]))
                (remove nil?))

          existing-provides
          (into #{} lookup-xf provides)]

      (cond
        ;; don't merge .cljc file if a .cljs of the same name exists
        (and cljc? (contains? (:sources state) cljs-name))
        ;; less noise by not complaining, some offenders in cljs package
        (do #_(util/log state {:type :bad-resource
                               :reason :cljs-over-cljc
                               :name name
                               :cljc-name name
                               :cljs-name cljs-name
                               :msg (format "File conflict: \"%s\" -> \"%s\" (using \"%s\")" name cljs-name cljs-name)})
          state)

        ;; if a .cljc exists for a .cljs file
        ;; overrides provides from .cljc with provides in .cljs
        (and (is-cljs? name) (contains? (:sources state) cljc-name))
        (-> state
            (unmerge-resource cljc-name)
            (assoc-in [:sources name] src)
            (merge-provides name provides))

        ;; ensure that files do not have conflicting provides
        (and (seq existing-provides)
             (not (every? #(is-same-resource? src (get-in state [:sources %])) existing-provides)))
        (do (util/log state
              {:type :provide-conflict
               :name name
               :source-path (:source-path src)
               :provides provides
               :conflict-with
               (reduce
                 (fn [m src-name]
                   (let [{:keys [source-path provides]}
                         (get-in state [:sources src-name])]
                     (assoc m (str source-path "/" src-name) provides)))
                 {}
                 existing-provides)})
            state)

        :no-conflict
        (-> state
            (assoc-in [:sources name] src)
            (merge-provides name provides))))))

(defn merge-resources [state srcs]
  (reduce merge-resource state srcs))

;;; COMPILE STEPS

(defn do-find-resources-in-path [state path]
  {:pre [(util/compiler-state? state)]}
  (if (is-jar? path)
    (find-jar-resources state path)
    (find-fs-resources state path)))

(defn should-exclude-classpath [exclude ^File file]
  (let [abs-path (.getAbsolutePath file)]
    (boolean (some #(re-find % abs-path) exclude))))

(defn merge-resources-in-path
  ([state path]
   (merge-resources-in-path state path {:reloadable true}))
  ([state path path-opts]
   (let [file (.getCanonicalFile (io/file path))
         abs-path (.getCanonicalPath file)]
     ;; checkout deps with a lot of symlinks can cause duplicates on classpath
     (if (contains? (:source-paths state) abs-path)
       state
       (let [{:keys [resources externs] :as dir-contents}
             (do-find-resources-in-path state abs-path)

             state
             (if (.isDirectory file)
               (assoc-in state [:source-paths abs-path]
                 (assoc path-opts :file file :path abs-path))
               state)]

         (-> state
             (update :deps-externs assoc abs-path externs)
             (merge-resources resources)))))))

(defn find-resources
  "finds cljs resources in the given path"
  ([state path]
   (find-resources state path {:reloadable true}))
  ([state path opts]
   (util/with-logged-time
     [state {:type :find-resources
             :path path}]
     (let [file (io/file path)
           abs-path (.getCanonicalPath file)]
       (when-not (.exists file)
         (throw (ex-info (format "\"%s\" does not exist" path) {:path path})))

       (if (contains? (:source-paths state) abs-path)
         (do (util/log state {:type :duplicate-path
                              :path path})
             state)
         (merge-resources-in-path state path opts))
       ))))

(defn find-resources-in-classpath
  "finds all cljs resources in the classpath (ignores resources)"
  ([state]
   (find-resources-in-classpath state {:exclude (:classpath-excludes state [])}))
  ([state {:keys [exclude]}]
   (util/with-logged-time
     [state {:type :find-resources-classpath}]
     (let [paths
           (->> (cp/classpath)
                (remove #(should-exclude-classpath exclude %)))]
       (reduce merge-resources-in-path state paths)
       ))))

(def ^:dynamic *in-compiler-env* false)

(defmacro with-compiler-env
  "compiler env is a rather big piece of dynamic state
   so we take it out when needed and put the updated version back when done
   doesn't carry the atom arround cause the compiler state itself should be persistent
   thus it should provide safe points

   the body should yield the updated compiler state and not touch the compiler env

   I don't touch the compiler env itself yet at all, might do for some metadata later"
  [state & body]
  `(do (when *in-compiler-env*
         (throw (ex-info "already in compiler env" {})))
       (let [dyn-env# (atom (:compiler-env ~state))
             new-state# (binding [env/*compiler* dyn-env#
                                  *in-compiler-env* true]
                          ~@body)]
         (assoc new-state# :compiler-env @dyn-env#))))

(defn swap-compiler-env!
  [state update-fn & args]
  (if *in-compiler-env*
    (do (swap! env/*compiler* (fn [current] (apply update-fn current args)))
        state)
    (update state :compiler-env (fn [current] (apply update-fn current args)))))

(defn discover-macros [state]
  ;; build {macro-ns #{used-by-source-by-name ...}}
  (let [macro-info
        (->> (:sources state)
             (vals)
             (filter #(seq (:macro-namespaces %)))
             (reduce (fn [macro-info {:keys [macro-namespaces name]}]
                       (reduce (fn [macro-info macro-ns]
                                 (update-in macro-info [macro-ns] set-conj name))
                         macro-info
                         macro-namespaces))
               {})
             (map (fn [[macro-ns used-by]]
                    (let [name (str (ns->path macro-ns) ".clj")
                          url (io/resource name)
                          ;; FIXME: clean this up, must look for .clj and .cljc
                          [name url] (if url
                                       [name url]
                                       (let [name (str name "c")]
                                         [name (io/resource name)]))]
                      #_(when-not url (util/log-warning logger (format "Macro namespace: %s not found, required by %s" macro-ns used-by)))
                      {:ns macro-ns
                       :used-by used-by
                       :name name
                       :url url})))
             ;; always get last modified for macro source
             (map (fn [{:keys [url] :as info}]
                    (if (nil? url)
                      info
                      (let [con (.openConnection url)]
                        (assoc info :last-modified (.getLastModified con)))
                      )))
             ;; get file (if not in jar)
             (map (fn [{:keys [^URL url] :as info}]
                    (if (nil? url)
                      info
                      (if (not= "file" (.getProtocol url))
                        info
                        (let [file (io/file (.getPath url))]
                          (assoc info :file file))))))
             (map (juxt :ns identity))
             (into {}))]
    (assoc state :macros macro-info)
    ))

(defn set-default-compiler-env
  [state]
  (cond-> state
    (nil? (:compiler-env state))
    (assoc :compiler-env @(env/default-compiler-env (:compiler-options state)))))

(defn generate-npm-resource [{:keys [emit-js-require] :as state} js-require js-ns-alias]
  (let [name
        (str js-ns-alias ".js")

        rc
        {:type :js
         :name name
         :js-name name
         :js-module js-require
         :provides #{js-ns-alias}
         :requires #{}
         :require-order []
         :input (atom (str "goog.provide(\"" js-ns-alias "\");\n"
                           #_(->> provides
                                  (map (fn [provide]
                                         (str "goog.provide(\"" provide "\");")))
                                  (str/join "\n"))
                           "\n"
                           (if emit-js-require
                             (str js-ns-alias " = require(\"" js-require "\");\n")
                             (str js-ns-alias " = window[\"npm$modules\"][" (pr-str js-require) "];\n"))))
         :last-modified 0}]

    (-> state
        (assoc-in [:sources name] rc)
        (assoc-in [:provide->source js-ns-alias] name)
        ;; FIXME: properly identify what :js-module-index is supposed to be
        (update-in [:compiler-env :js-module-index] assoc (str js-ns-alias) js-ns-alias)
        )))

(defn generate-npm-resources
  ([state]
   (let [js-ns-aliases
         (->> (:sources state)
              (vals)
              (map :js-ns-aliases)
              (reduce merge {}))]
     (generate-npm-resources state js-ns-aliases)))
  ([state js-requires]
   (reduce-kv generate-npm-resource state js-requires)))

(defn make-provide-index [state]
  (let [idx
        (into {} (for [{:keys [name provides]} (vals (:sources state))
                       provide provides]
                   [provide name]
                   ))]

    (assoc state :provide->source idx)))

(defn finalize-config
  "should be called AFTER all resources have been discovered (ie. after find-resources...)"
  [state]
  (-> state
      (discover-macros)
      (set-default-compiler-env)
      (generate-npm-resources)
      (make-provide-index)))

(defn reset-modules [state]
  (-> state
      (assoc :modules {})
      (dissoc :default-module :build-modules)
      ))

(defn configure-module
  ([state module-name module-entries depends-on]
   (configure-module state module-name module-entries depends-on {}))
  ([state module-name module-entries depends-on mod-attrs]
   (when-not (keyword? module-name)
     (throw (ex-info "module name should be a keyword" {:module-name module-name})))
   (when-not (every? keyword? depends-on)
     (throw (ex-info "module deps should be keywords" {:module-deps depends-on})))

   (let [is-default?
         (not (seq depends-on))

         mod
         (merge mod-attrs
                {:name module-name
                 :js-name (or (:js-name mod-attrs)
                              (str (name module-name) ".js"))
                 :entries module-entries
                 :depends-on (into #{} depends-on)
                 :default is-default?})]

     (when is-default?
       (when-let [default (:default-module state)]
         (prn [:default default :new module-name])
         (throw (ex-info "default module already defined, are you missing deps?"
                  {:default default :wants-to-be-default module-name}))))

     (-> state
         (assoc-in [:modules module-name] mod)
         (cond->
           is-default?
           (assoc :default-module module-name)
           )))))

(defn dump-js-modules [modules]
  (doseq [js-mod modules]
    (prn [:js-mod (.getThisAndAllDependencies js-mod)])
    (doseq [input (.getInputs js-mod)]
      (prn [:js-mod input]))))

;; module related stuff

(defn topo-sort-modules*
  [{:keys [modules deps visited] :as state} name]
  (let [{:keys [depends-on] :as mod}
        (get modules name)]
    (cond
      (nil? mod)
      (throw (ex-info "module not defined" {:missing name}))

      (contains? deps name)
      (throw (ex-info "module circular dependeny" {:deps deps :name name}))

      (contains? visited name)
      state

      :else
      (-> state
          (update :visited conj name)
          (update :deps conj name)
          (as-> state
            (reduce topo-sort-modules* state depends-on))
          (update :deps disj name)
          (update :order conj name)))))

(defn topo-sort-modules [modules]
  (let [{:keys [deps visited order] :as result}
        (reduce
          topo-sort-modules*
          {:deps #{}
           :visited #{}
           :order []
           :modules modules}
          (keys modules))]

    (assert (empty? deps))
    (assert (= (count visited) (count modules)))

    order
    ))

(defn sort-and-compact-modules
  "sorts modules in dependency order and remove sources provided by parent deps"
  [{:keys [modules] :as state}]
  (when-not (seq modules)
    (throw (ex-info "no modules defined" {})))

  ;; if only one module is defined we dont need all this work
  (if (= 1 (count modules))
    (vec (vals modules))
    ;; else: multiple modules must be sorted in dependency order
    (let [module-order
          (topo-sort-modules modules)

          src-refs
          (->> (for [mod-id module-order
                     :let [{:keys [sources depends-on] :as mod} (get modules mod-id)]
                     src sources]
                 [src mod-id])
               (reduce
                 (fn [src-refs [src dep]]
                   (update src-refs src set-conj dep))
                 {}))

          ;; could be optimized
          find-mod-deps
          (fn find-mod-deps [mod-id]
            (let [{:keys [name depends-on] :as mod}
                  (get modules mod-id)]
              (reduce set/union (into #{name} depends-on) (map find-mod-deps depends-on))))

          find-closest-common-dependency
          (fn [src deps]
            (let [all
                  (map #(find-mod-deps %) deps)

                  common
                  (apply set/intersection all)]
              (condp = (count common)
                0
                (throw (ex-info "no common dependency found for src" {:src src :deps deps}))

                1
                (first common)

                (->> module-order
                     (reverse)
                     (drop-while #(not (contains? common %)))
                     (first)))))

          all-sources
          (->> module-order
               (mapcat #(get-in modules [% :sources]))
               (distinct)
               (into []))

          final-sources
          (reduce
            (fn [final src]
              (let [deps
                    (get src-refs src)

                    target-mod
                    (if (= 1 (count deps))
                      (first deps)
                      (let [target-mod (find-closest-common-dependency src deps)]

                        ;; only warn when a file is moved to a module it wouldn't be in naturally
                        (when-not (contains? deps target-mod)
                          (util/log state {:type :module-move
                                           :src src
                                           :deps deps
                                           :moved-to target-mod}))
                        target-mod))]

                (update final target-mod vec-conj src)))
            {}
            all-sources)]

      (->> module-order
           (map (fn [mod-id]
                  (let [sources
                        (get final-sources mod-id)]
                    (when (empty? sources)
                      (util/log state {:type :empty-module :mod-id mod-id}))
                    (-> (get modules mod-id)
                        (assoc :sources sources)))))
           (into [])))))

(defn extract-warnings
  "collect warnings for listed sources only"
  [state source-names]
  (->> (for [name source-names
             :let [{:keys [warnings] :as src}
                   (get-in state [:sources name])]
             warning warnings]
         (assoc warning :source-name name))
       (into [])))

(defn print-warnings!
  "print warnings after building modules, repeat warnings for files that weren't recompiled!"
  [state]
  (doseq [{:keys [name warnings] :as src}
          (->> (:build-sources state)
               (map #(get-in state [:sources %]))
               (sort-by :name)
               (filter #(seq (:warnings %))))]
    (doseq [warning warnings]
      (util/log state {:type :warning
                       :name name
                       :warning warning})
      )))

(defn get-deps-for-entries [state entries]
  (->> entries
       (mapcat #(get-deps-for-ns state %))
       (distinct)
       (into [])))

(defn do-analyze-module
  "resolve all deps for a given module, based on specified :entries
   will update state for each module with :sources, a list of sources needed to compile this module
   will add pseudo-resources if :append-js or :prepend-js are present"
  [state {:keys [name entries append-js prepend-js] :as module}]
  (let [sources
        (get-deps-for-entries state entries)

        mod-name
        (clojure.core/name name)

        state
        (assoc-in state [:modules name :sources] sources)

        pseudo-rc
        (fn [name provide js]
          {:type :js
           :name name
           :js-name (util/flat-filename name)
           :provides #{provide}
           :requires #{}
           :require-order []
           :last-modified 0
           :input (atom js)
           :output js})

        state
        (if-not (seq prepend-js)
          state
          (let [prepend-name
                (str "shadow/module/prepend/" mod-name ".js")

                prepend-provide
                (symbol (str "shadow.module.prepend." mod-name))]

            (-> state
                (update :sources assoc prepend-name (pseudo-rc prepend-name prepend-provide prepend-js))
                (update-in [:modules name :sources] #(into [prepend-name] %))
                )))

        state
        (if-not (seq append-js)
          state
          (let [append-name
                (str "shadow/module/append/" mod-name ".js")

                append-provide
                (symbol (str "shadow.module.append." mod-name))]

            (-> state
                (update :sources assoc append-name (pseudo-rc append-name append-provide append-js))
                (update-in [:modules name :sources] conj append-name)
                )))]

    state
    ))

(defn add-foreign
  [state name provides requires js-source externs-source]
  {:pre [(string? name)
         (set? provides)
         (seq provides)
         (set? requires)
         (string? js-source)
         (seq js-source)
         (string? externs-source)
         (seq externs-source)]}

  (merge-resource state
    {:type :foreign
     :name name
     :js-name (util/flat-filename name)
     :provides provides
     :requires requires
     ;; FIXME: should allow getting a vector as provides instead
     :require-order (into [] requires)
     :input (atom js-source)
     :externs-source externs-source
     :last-modified 0
     }))

(defn maybe-compile-js [state {:keys [input module-type] :as src}]
  (if module-type
    (closure/compile-es6 state src)
    (assoc src :output @input)))

(defn compile-foreign [state {:keys [input] :as src}]
  (assoc src :output @input))

(defn generate-output-for-source [state {:keys [name type output warnings] :as src}]
  {:post [(string? (:output %))]}
  (when (= name "goog/base.js")
    (throw (ex-info "trying to compile goog/base.js" {})))

  (when-not (valid-resource? src)
    (s/explain ::resource src)
    (throw (ex-info "compiling invalid resource"
             (assoc (s/explain-data ::resource src)
               :tag :invalid-resource))))

  (if (and (seq output)
           ;; always recompile files with warnings
           (not (seq warnings)))
    src
    (case type
      :foreign
      (compile-foreign state src)

      :js
      (maybe-compile-js state src)

      :cljs
      (maybe-compile-cljs state src))))


(defonce REDEF-LOCK (Object.))

(defn load-core-noop [])

(defmacro shadow-redefs [& body]
  ;; FIXME: can't redef without the lock
  ;; but the lock prevents multiple builds from running in parallel
  #_(locking REDEF-LOCK
      (with-redefs [ana/parse shadow-parse
                    ana/load-core load-core-noop
                    ana/analyze-form shadow-analyze-form]
        ~@body))

  `(binding [*compiling* true]
     ~@body))

(defn do-compile-sources
  "compiles with just the main thread, can do partial compiles assuming deps are compiled"
  [state source-names]
  {:pre [(every? string? source-names)]}
  (with-compiler-env state
    (ana/load-core)
    (shadow-redefs
      (reduce
        (fn [state source-name]
          (let [src (get-in state [:sources source-name])
                compiled-src (generate-output-for-source state src)]
            (assoc-in state [:sources source-name] compiled-src)))
        state
        source-names))))

(defn par-compile-one
  [state ready-ref errors-ref source-name]
  (let [{:keys [requires] :as src}
        (get-in state [:sources source-name])]

    (loop [idle-count 0]
      (let [ready @ready-ref]
        (cond
          ;; skip work if errors occured
          (seq @errors-ref)
          src

          ;; only compile once all dependencies are compiled
          ;; FIXME: sleep is not great, cljs.core takes a couple of sec to compile
          ;; this will spin a couple hundred times, doing additional work
          ;; don't increase the sleep time since many files compile in the 5-10 range
          (not (set/superset? ready requires))
          (do (Thread/sleep 5)
              ;; diagnostic warning if we are still waiting for something to compile for 15+ sec
              ;; should only happen in case of deadlocks or missing/broken requires
              ;; should probably add a real timeout and fail the build instead of looping forever
              (if (>= idle-count 3000)
                (let [pending (set/difference requires ready)]
                  (util/log state {:type :pending-compile
                                   :source-name source-name
                                   :pending pending})
                  (recur 0))
                (recur (inc idle-count))))

          :ready-to-compile
          (try
            (let [expected-provides
                  (:provides src)

                  {:keys [provides] :as compiled-src}
                  (generate-output-for-source state src)]

              (when (not= expected-provides provides)
                (throw (ex-info "generated output did not produce expected provides"
                         {:expected expected-provides
                          :provides provides
                          :source-name source-name})))

              (swap! ready-ref set/union provides)
              compiled-src)
            (catch Exception e
              (swap! errors-ref assoc source-name e)
              src
              )))))))

(defn par-compile-sources
  "compile files in parallel, files MUST be in dependency order and ALL dependencies must be present
   this cannot do a partial incremental compile"
  [state source-names]
  (with-compiler-env state
    (ana/load-core)
    (shadow-redefs
      (let [;; namespaces that are ready to be used
            ready
            (atom #{'goog})

            ;; source-name -> exception
            errors
            (atom {})

            exec
            (or (:executor state)
                (Executors/newFixedThreadPool (:n-compile-threads state)))

            tasks
            (->> (for [source-name source-names]
                   ;; bound-fn for with-compiler-state
                   (let [task-fn (bound-fn [] (par-compile-one state ready errors source-name))]
                     ;; things go WTF without the type tags, tasks will return nil
                     (.submit ^ExecutorService exec ^Callable task-fn)))
                 (doall) ;; force submit all, then deref
                 (into [] (map deref)))]

        ;; only shutdown executor if we werent given one
        (when-not (contains? state :executor)
          ;; FIXME: might deadlock here if any of the derefs fail
          (.shutdown exec))

        ;; unlikely to encounter 2 concurrent errors
        ;; so unpack for a single error for better stacktrace
        (let [errs @errors]
          (case (count errs)
            0 nil
            1 (let [[name err] (first errs)]
                (throw err))
            (throw (ex-info "compilation failed" errs))))

        (-> state
            (update :sources (fn [sources]
                               (reduce
                                 (fn [sources {:keys [name] :as src}]
                                   (when (nil? src)
                                     (throw (ex-info "a compile task returned nil?" {})))
                                   (assoc sources name src))
                                 sources
                                 tasks)))
            )))))

(defn names-compiled-in-last-build [{:keys [build-sources build-start] :as state}]
  (->> build-sources
       (filter
         (fn [name]
           (let [{:keys [compiled compiled-at cached] :as rc}
                 (get-in state [:sources name])]
             (and (not cached) compiled (> compiled-at build-start))
             )))
       (into [])))

(defn compile-sources
  [{:keys [n-compile-threads] :as state} source-names]
  "compile a list of sources by name,
   requires that the names are in dependency order
   requires that ALL of the dependencies NOT in source names are already compiled
   eg. you cannot just compile [\"clojure/string.cljs\"] as it requires other files to be compiled first"
  (util/log state {:type :compile-sources
                   :n-compile-threads n-compile-threads
                   :source-names source-names})
  (-> state
      (assoc :build-start (System/currentTimeMillis))
      (cond->
        (or (:executor state) (> n-compile-threads 1))
        (par-compile-sources source-names)

        (not (> n-compile-threads 1))
        (do-compile-sources source-names))
      (assoc :build-done (System/currentTimeMillis))))



(defn prepare-compile
  "prepares for compilation (eg. create source lookup index)"
  [state]
  (finalize-config state))

(defn md5hex [^String text]
  (let [bytes
        (.getBytes text)

        md
        (doto (MessageDigest/getInstance "MD5")
          (.update bytes))

        sig
        (.digest md)]

    (DatatypeConverter/printHexBinary sig)
    ))

(defn make-foreign-bundle [state {:keys [name sources] :as mod}]
  (let [foreign-srcs
        (->> sources
             (map #(get-in state [:sources %]))
             (filter util/foreign?))]

    (if-not (seq foreign-srcs)
      mod
      (let [output
            (->> foreign-srcs
                 (map :output)
                 (str/join "\n"))

            provides
            (->> foreign-srcs
                 (map :provides)
                 (reduce set/union #{}))

            md5
            (md5hex output)

            foreign-name
            (str (clojure.core/name name) "-foreign-" md5 ".js")]

        (assoc mod
          :foreign-files
          [{:js-name foreign-name
            :provides provides
            :output output
            :sources (into [] (map :name) foreign-srcs)
            }])))
    ))

(defn make-foreign-bundles [{:keys [build-modules] :as state}]
  (let [new-mods
        (into [] (map #(make-foreign-bundle state %)) build-modules)]
    (assoc state :build-modules new-mods)))

(defn prepare-modules
  "prepares :modules for compilation (sort and compacts duplicate sources)"
  [state]
  (let [state
        (reduce do-analyze-module state (-> state :modules (vals)))

        modules
        (sort-and-compact-modules state)]
    (assoc state
      :build-modules modules
      :build-sources (into [] (mapcat :sources modules)))))

(defn compile-modules*
  "compiles source based on :build-modules (created by prepare-modules)"
  [{:keys [build-sources bundle-foreign] :as state}]
  (-> state
      (compile-sources build-sources)
      (cond->
        (not= bundle-foreign :inline)
        (make-foreign-bundles))))

(defn compile-modules
  "compiles according to configured :modules"
  [state]
  (util/with-logged-time
    [state {:type :compile-modules}]
    (let [state
          (-> state
              (prepare-compile)
              (prepare-modules)
              (compile-modules*))]
      (print-warnings! state)
      state
      )))

(defn compile-all-for-ns
  "compiles all files required by ns"
  [state ns]
  (let [state
        (prepare-compile state)

        deps
        (get-deps-for-ns state ns)]

    (-> state
        (assoc :build-sources deps)
        (compile-sources deps))
    ))

(defn compile-all-for-src
  "compiles all files required by src name"
  [state src-name]
  (let [state
        (prepare-compile state)

        deps
        (get-deps-for-src state src-name)]

    (-> state
        (assoc :build-sources deps)
        (compile-sources deps))
    ))

(defn add-closure-configurator
  "adds a closure configurator 2-arity function that will be called before the compiler is invoked
   signature of the callback is (fn [compiler compiler-options])

   Compiler and CompilerOptions are mutable objects, the return value of the callback is ignored

   CLJS default configuration is done first, all configurators are applied later and may override
   any options.

   See:
   com.google.javascript.jscomp.Compiler
   com.google.javascript.jscomp.CompilerOptions"
  [state callback]
  (update state :closure-configurators conj callback))

(defn closure-check [state]
  (closure/check state))

(defn closure-optimize
  ([state]
   (closure/optimize state))
  ([state optimizations]
   (-> state
       (update :compiler-options assoc :optimizations optimizations)
       (closure/optimize))))

(defn get-reloadable-source-paths [state]
  (->> state
       :source-paths
       (vals)
       (filter :reloadable)
       (map :path)
       (set)))

(defn reload-source [{:keys [url] :as rc}]
  (assoc rc :input (delay (slurp url))))

(defn reset-resource-by-name [state name]
  (let [{:keys [^File file] :as rc} (get-in state [:sources name])]
    ;; only resource that have a file associated with them can be reloaded (?)
    (if (nil? file)
      state
      (let [new-rc
            (-> rc
                (dissoc :ns :ns-info :requires :require-order :provides :output :compiled :compiled-at)
                (reload-source)
                (as-> src'
                  (inspect-resource state src'))
                (cond-> file
                  (assoc :last-modified (.lastModified file))))]
        (-> state
            (unmerge-resource name)
            (merge-resource new-rc))))
    ))

(defn find-dependent-names
  [state ns-sym]
  (->> (:sources state)
       (vals)
       (filter (fn [{:keys [requires]}]
                 (contains? requires ns-sym)))
       (map :name)
       (into #{})
       ))

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
      )))

(defn reset-resources-using-macro [state macro-ns]
  (let [names (find-resources-using-macro state macro-ns)]
    (reduce reset-resource-by-name state names)
    ))

(defn ^:deprecated scan-for-new-files
  "scans the reloadable paths for new files

   returns a seq of resource maps with a {:scan :new} value"
  [{:keys [sources] :as state}]
  (let [reloadable-paths (get-reloadable-source-paths state)
        known-files (->> sources
                         (vals)
                         (map (fn [{:keys [source-path name]}]
                                [source-path name]))
                         (into #{}))]
    (->> reloadable-paths
         (mapcat #(find-fs-resources state %))
         (remove (fn [{:keys [source-path name]}]
                   (contains? known-files [source-path name])))
         (map #(assoc % :scan :new))
         (into []))))

(defn ^:deprecated scan-for-modified-files
  "scans known sources for modified or deleted files

  returns a seq of resource maps with a :scan key which is either :modified :delete

  modified macros will cause all files using to to be returned as well
  although the files weren't modified physically the macro output may have changed"
  [{:keys [sources macros] :as state}]
  (let [reloadable-paths (get-reloadable-source-paths state)]

    ;; FIXME: separate macro scanning from normal scanning
    (let [modified-macros
          (->> macros
               (vals)
               (filter :file)
               (reduce
                 (fn [result {:keys [ns file last-modified] :as macro}]
                   (let [new-mod (.lastModified file)]
                     (if (<= new-mod last-modified)
                       result
                       (let [macro (assoc macro
                                     :scan :macro
                                     :last-modified new-mod)]

                         (conj result macro)))))
                 []))

          affected-by-macros
          (->> modified-macros
               (map :ns)
               (map #(find-resources-using-macro state %))
               (reduce set/union))]

      (->> (vals sources)
           (filter #(contains? reloadable-paths (:source-path %)))
           (filter :file)
           (reduce
             (fn [result {:keys [name ^File file last-modified] :as rc}]
               (cond
                 (not (.exists file))
                 (conj result (assoc rc :scan :delete))

                 (contains? affected-by-macros name)
                 (conj result (assoc rc :scan :modified))

                 (> (.lastModified file) last-modified)
                 (conj result (assoc rc :scan :modified))

                 :else
                 result))
             modified-macros)))))

(defn ^:deprecated scan-files
  "scans for new and modified files
   returns resources maps with a :scan key with is either :new :modified :delete"
  [state]
  (concat (scan-for-modified-files state)
    (scan-for-new-files state)))

(defn ^:deprecated wait-for-modified-files!
  "blocks current thread waiting for modified files
  return resource maps with a :scan key which is either :new :modified :delete"
  [{:keys [sources] :as initial-state}]
  (util/log initial-state {:type :waiting-for-modified-files})
  (loop [state initial-state
         i 0]

    ;; don't scan for new files too frequently
    ;; quite a bit more expensive than just checking a known file

    (let [modified (scan-for-modified-files state)
          modified (if (zero? (mod i 5))
                     (concat modified (scan-for-new-files state))
                     modified)]
      (if (seq modified)
        modified
        (do (Thread/sleep 500)
            (recur state
              (inc i)))))))

(defn ^:deprecated reload-modified-resource
  [state {:keys [scan name file ns] :as rc}]
  (util/log state {:type :reload
                   :action scan
                   :ns ns
                   :name name})
  (case scan
    :macro
    (do (try
          ;; FIXME: :reload enough probably?
          (require ns :reload-all)
          (catch Exception e
            (util/log state {:type :macro-error
                             :ns ns
                             :name name
                             :file file
                             :exception e})))
        (assoc-in state [:macros ns] (dissoc rc :scan)))

    :delete
    (unmerge-resource state (:name rc))

    :new
    (merge-resource state (inspect-resource state (dissoc rc :scan)))

    :modified
    (let [dependents (find-dependent-names state ns)]
      ;; modified files also trigger recompile of all its dependents
      (reduce reset-resource-by-name state (cons name dependents))
      )))

(defn ^:deprecated reload-modified-files!
  [state scan-results]
  (as-> state $state
    (reduce reload-modified-resource $state scan-results)
    ;; FIXME: this is kinda ugly but need a way to discover newly required macros
    (discover-macros $state)
    ))

(defn ^:deprecated wait-and-reload!
  "wait for modified files, reload them and return reloaded state"
  [state]
  (->> (wait-for-modified-files! state)
       (reload-modified-files! state)))

;; configuration stuff
(defn ^{:deprecated "moved to a closure pass, always done on closure optimize"}
enable-emit-constants [state]
  state)

(defn enable-source-maps [state]
  (-> state
      (update :compiler-options assoc :source-map "/dev/null")
      (assoc :source-map-comment true)
      (assoc :source-map true)))

(defn merge-compiler-options [state opts]
  (update state :compiler-options merge opts))

(defn merge-build-options [state opts]
  (merge state opts))

(defn merge-build-options [state opts]
  (reduce-kv
    (fn [state key value]
      (cond
        (and (map? value)
             (map? (get state key)))
        (update state key merge value)

        :default
        (assoc state key value)
        ))
    state
    opts))

(defn get-closure-compiler [state]
  (::cc state))

(def stdout-log
  (reify log/BuildLog
    (util/log* [_ state evt]
      (locking stdout-lock
        (println (log/event-text evt))
        ))))

(defn init-state []
  (let [work-dir (io/file "target")]
    {::util/is-compiler-state true

     :ignore-patterns
     #{#"^node_modules/"
       #"^goog/demos/"
       #".aot.js$"
       #"^goog/(.+)_test.js$"
       #"^public/"}

     :classpath-excludes
     [#"resources(/?)$"
      #"classes(/?)$"
      #"java(/?)$"]

     ::cc (closure/make-closure-compiler)

     ;; map of {source-path [global-extern-names ...]}
     ;; provided by the deps.cljs of all source-paths
     :deps-externs
     {}

     :analyzer-passes
     [ana/infer-type]

     :compiler-options
     {:optimizations :none
      :static-fns true
      :elide-asserts false

      :closure-warnings
      {:check-types :off}}

     :closure-defines
     {"goog.DEBUG" false
      "goog.LOCALE" "en"
      "goog.TRANSPILE" "never"}

     :runtime
     {:print-fn :none}

     :module-format :goog
     :emit-js-require true

     :use-file-min true

     :bundle-foreign :inline
     :dev-inline-js true

     :ns-aliases
     '{clojure.pprint cljs.pprint
       clojure.spec.alpha cljs.spec.alpha}

     ;; (fn [compiler-state ns] alias-ns|nil)
     ;; CLJS by default aliases ALL clojure.* namespaces to cljs.* if the cljs.* version exist
     ;; I prefer a static map since it may be extended by the user and avoids touching the filesystem
     :ns-alias-fn
     (fn [{:keys [ns-aliases] :as state} ns]
       (get ns-aliases ns))

     :closure-configurators []

     ;; :none supprt files are placed into <output-dir>/<cljs-runtime-path>/cljs/core.js
     ;; this used to be just "src" but that is too generic and easily breaks something
     ;; if output-dir is equal to the current working directory
     :cljs-runtime-path "cljs-runtime"

     :work-dir work-dir

     :manifest-cache-dir
     (let [dir (io/file work-dir "shadow-cljs" "jar-manifest")]
       (io/make-parents dir)
       dir)

     :cache-dir (io/file work-dir "shadow-cljs" "cljs-cache")
     :cache-level :all

     :output-dir (io/file "public" "js")
     :asset-path "js"

     :n-compile-threads (.. Runtime getRuntime availableProcessors)

     :source-paths {}

     :logger
     stdout-log

     :unoptimizable
     (when-let [imul (io/resource "cljs/imul.js")]
       (slurp imul))}))

(defn watch-and-repeat! [state callback]
  (loop [state (callback state [])]
    (let [modified (wait-for-modified-files! state)
          state (reload-modified-files! state modified)]
      (recur (try
               (callback state (mapv :name modified))
               (catch Exception e
                 (println (str "COMPILATION FAILED: " e))
                 (.printStackTrace e)
                 state))))))

(defn has-tests? [{:keys [requires] :as rc}]
  (contains? requires 'cljs.test))

;; api delegation

(defn flush-unoptimized [state]
  (output/flush-unoptimized state))

(defn flush-modules-to-disk [state]
  (output/flush-optimized state))

(defn flush-sources-by-name [state]
  (output/flush-sources-by-name state))