(ns shadow.build.classpath
  (:require [clojure.tools.reader.reader-types :as readers]
            [clojure.tools.reader :as reader]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.classpath :as cp]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            [cljs.tagged-literals :as tags]
            [shadow.spec :as ss]
            [shadow.build.resource :as rc]
            [shadow.cljs.util :as util]
            [shadow.build.cache :as cache]
            [shadow.build.ns-form :as ns-form]
            [shadow.build.config :as config]
            [shadow.build.cljs-bridge :as cljs-bridge]
            )
  (:import (com.google.javascript.jscomp.deps JsFileParser)
           (java.io StringReader PushbackReader File)
           (java.util.jar JarFile JarEntry)
           (java.net URL)
           (java.util.zip ZipException)
           (shadow.build.closure ErrorCollector)))

(def CACHE-TIMESTAMP (System/currentTimeMillis))

(defn service? [x]
  (and (map? x) (::service x)))

(defn inspect-goog [state {:keys [resource-name url] :as rc}]
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

(defn inspect-cljs
  "looks at the first form in a .cljs file, analyzes it if (ns ...) and returns the updated resource
   with ns-related infos"
  [state {:keys [url macros-ns] :as rc}]
  (let [{:keys [name deps requires] :as ast}
        (cljs-bridge/get-resource-info url)

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
          (assoc :macros-ns true)))))

(defn inspect-resource
  [state {:keys [resource-name url] :as rc}]
  (cond
    (util/is-js-file? resource-name)
    (->> (assoc rc :type :goog)
         (inspect-goog state))

    (util/is-cljs-file? resource-name)
    (->> (assoc rc :type :cljs)
         (inspect-cljs state))

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

(defn process-deps-cljs [cp source-path resources]
  {:pre [(sequential? resources)
         (util/file? source-path)]}

  (let [index
        (->> resources
             (map (juxt :resource-name identity))
             (into {}))

        deps-cljs
        (get index "deps.cljs")]

    (if (nil? deps-cljs)
      {:foreign-libs []
       :externs []
       :resources resources}

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
         :externs extern-rcs}
        ))))

(defn find-jar-resources* [cp file]
  (try
    (let [jar-path
          (.getCanonicalPath file)

          jar-file
          (JarFile. file)

          last-modified
          (.lastModified file)

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
                    (conj! {:resource-id [::resource name]
                            :resource-name (rc/normalize-name name)
                            :cache-key last-modified
                            :last-modified last-modified
                            :url url
                            :from-jar true})
                    (recur)
                    )))))))

    (catch ZipException e
      ;; node-jre contains a weird .jar that isn't actually a jar, just pretend its empty
      (log/debug "filtered bad jar" file e)
      {})

    (catch Exception e
      (throw (ex-info (str "failed to generate jar manifest for file: " file) {:file file} e)))
    ))

(defn set-output-name [{:keys [resource-name] :as rc}]
  (assoc rc :output-name (util/flat-js-name resource-name)))

(defn inspect-resources [cp {:keys [resources] :as contents}]
  (assoc contents :resources
    (->> resources
         (map set-output-name)
         (map (fn [src]
                (try
                  (inspect-resource cp src)
                  (catch Exception e
                    (log/warnf "failed to inspect resource \"%s\", it will not be available." (or (:file src)
                                                                                                  (:url src)
                                                                                                  (:resource-name src)))
                    nil))))
         (remove nil?)
         (remove (fn [{:keys [type module-type]}]
                   ;; only want goog resources here
                   ;; es6 and others will be done by shadow.build.npm
                   (and (= :goog type) (not= "goog" module-type))))
         (remove #(empty? (:provides %)))
         (into [])
         )))

(defn process-root-contents [cp source-path root-contents]
  {:pre [(sequential? root-contents)
         (util/file? source-path)]}

  (->> (process-deps-cljs cp source-path root-contents)
       (inspect-resources cp)))

(defn find-jar-resources
  [{:keys [manifest-cache-dir] :as cp} jar-file]
  (let [manifest-name
        (str (.lastModified jar-file) "-" (.getName jar-file) ".manifest")

        mfile
        (io/file manifest-cache-dir manifest-name)]

    (or (when (and (.exists mfile)
                   (>= (.lastModified mfile) (.lastModified jar-file))
                   (>= (.lastModified mfile) CACHE-TIMESTAMP))
          (try
            (cache/read-cache mfile)
            (catch Exception e
              (log/info ::manifest-error :file mfile :ex e)
              nil)))

        (let [jar-contents
              (->> (find-jar-resources* cp jar-file)
                   (process-root-contents cp jar-file))]
          (io/make-parents mfile)
          (cache/write-file mfile jar-contents)
          jar-contents))))

(defn make-fs-resource [file name]
  {:resource-id [::resource name]
   :resource-name name
   :cache-key (.lastModified file)
   :last-modified (.lastModified file)
   :file file
   :url (.toURL file)})

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

(defn find-resources [cp file]
  (if (util/is-jar? (.getName file))
    (find-jar-resources cp file)
    (find-fs-resources cp file)))

(defn should-exclude-classpath [exclude ^File file]
  (let [abs-path (.getAbsolutePath file)]
    (boolean (some #(re-find % abs-path) exclude))))

(defn get-classpath-entries [{:keys [classpath-excludes] :as cp}]
  (->> (cp/classpath)
       (remove #(should-exclude-classpath classpath-excludes %))
       (map #(.getCanonicalFile %))
       (into [])))

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

(defn index-rc-merge
  [index {:keys [type ns resource-name provides url] :as rc}]
  (cond
    ;; sanity check for development
    (not (rc/valid-resource? rc))
    (throw (ex-info "invalid resource" {}))

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
    ;; not really needed but cljs does this, so we should enforce it as well
    (and (= :cljs (:type rc))
         (symbol? (:ns rc))
         (let [expected-cljs (util/ns->cljs-filename (:ns rc))
               expected-cljc (str/replace expected-cljs #".cljs$" ".cljc")]
           (not (or (= resource-name expected-cljs)
                    (= resource-name expected-cljc)
                    ))))

    (do (log/infof "filename violation for ns %s, got: %s expected: %s (or .cljc)"
          (:ns rc)
          resource-name
          (util/ns->cljs-filename (:ns rc)))

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
        (log/infof "duplicate resource %s on classpath, using %s over %s" resource-name (:url conflict) (:url rc)))
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

          {:keys [file resource-id]}
          rc]

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
        (do (log/warnf "provide conflict for %s provided by %s and %s"
              provides
              resource-name
              (reduce
                (fn [m src-name]
                  (let [{:keys [source-path provides]}
                        (get-in index [:sources src-name])]
                    (assoc m (str source-path "/" src-name) provides)))
                {}
                existing-provides))
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

(defn index-file-add [index source-path file]
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

(defn index-file-remove [index source-path file]
  (let [abs-file (.getAbsoluteFile file)
        resource-name (get-in index [:file->name abs-file])]
    (if-not resource-name
      index
      (index-rc-remove index resource-name)
      )))

(defn start [cache-root]
  (let [index
        {:ignore-patterns
         #{#"^node_modules/"
           #"^goog/demos/"
           #".aot.js$"
           #"^goog/(.+)_test.js$"
           #"^public/"}

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

         ;; resource-name -> resource
         :sources {}}]

    {::service true
     ;; FIXME: maybe use an agent?
     ;; few of the functions working on the index will touch the filesystem
     ;; they only read so retries are fine but maybe agent would be good too
     ;; they are however annoying to coordinate and I typically want to
     ;; wait before moving on
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

(defn find-resource-by-name
  "returns nil if name is not on the classpath (or was filtered)"
  [{:keys [index-ref] :as cp} name]
  {:pre [(service? cp)
         (string? name)]}
  (get-in @index-ref [:sources name]))

(defn find-resource-by-file
  "returns nil if file is not registered on the classpath"
  [{:keys [index-ref] :as cp} file]
  {:pre [(service? cp)
         (util/is-file-instance? file)]}

  (let [abs-file (.getAbsoluteFile file)
        index @index-ref]
    (when-let [src-name (get-in index [:file->name abs-file])]
      (get-in index [:sources src-name]))))



(defn get-deps-externs [{:keys [index-ref] :as cp}]
  (->> (:deps-externs @index-ref)
       (vals)
       (mapcat identity)
       (into [])))

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



(comment
  ;; FIXME: implement correctly

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
        ))))



