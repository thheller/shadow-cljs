(ns shadow.cljs.launcher.standalone
  (:refer-clojure :exclude (add-classpath))
  (:require
    [clojure.tools.deps.alpha :as tdeps]
    [clojure.tools.deps.alpha.util.maven :as mvn]
    [clojure.java.io :as io]
    [clojure.data.json :as json]
    [clojure.string :as str])
  (:gen-class)
  (:import [java.io File]
           [clojure.lang DynamicClassLoader]))

(def unwanted-deps
  '#{org.clojure/clojure ;; can't run on 1.8
     ;; provided by launcher
     org.clojure/data.json
     org.clojure/tools.deps.alpha

     ;; just in case, added later
     thheller/shadow-cljs

     ;; provided by shadow-cljs
     org.clojure/clojurescript ;; we will always be on the latest version
     })

(defn drop-unwanted-deps [dependencies]
  (->> dependencies
       (remove (fn [[dep-id & _]]
                 (contains? unwanted-deps dep-id)))
       (into [])))

(defn add-exclusions [dependencies]
  (->> dependencies
       (map (fn [[dep-id version & modifiers :as dep]]
              (let [mods
                    (-> (apply hash-map modifiers)
                        (update :exclusions (fn [excl]
                                              (->> excl
                                                   (concat unwanted-deps)
                                                   (distinct)
                                                   (into [])))))]
                (reduce-kv conj [dep-id version] mods))))
       (into [])))

(defn modified-dependencies? [cp config]
  (or (not= (:version cp) (:version config))
      (not= (:dependencies cp) (:dependencies config))))

;; transform lein-style [foo/bar "1.2.3] into {foo/bar {:mvn/version "1.2.3"}}
(defn as-lib-map [deps]
  (reduce
    (fn [m [dep version & kv-pairs :as x]]
      (assoc m
        dep
        (-> {:mvn/version version}
            (cond->
              (seq kv-pairs)
              (merge (apply array-map kv-pairs)))
            )))
    {}
    deps))

(defn resolve-deps [{:keys [dependencies repositories] :as config}]
  (let [deps
        (as-lib-map dependencies)

        resolved-deps
        (tdeps/resolve-deps
          {:deps deps
           :mvn/repos (merge mvn/standard-repos repositories)}
          {})]

    (assoc config
      :resolved-deps
      resolved-deps
      :files
      (->> (vals resolved-deps)
           (mapcat :paths)
           ;; order shouldn't matter
           ;; but due to a clash with some AOT classes it does
           ;; thheller/shadow-cljs must be loaded
           ;; before cljs since that contains AOT classes as well
           ;; and will prevent the shadow-cljs hacks from being loaded
           ;; must refactor my hacks to use a different strategy
           ;; or rather write proper patches so the hacks aren't
           ;; required anymore at all ...
           (sort)
           (reverse)
           (into [])))
    ))

(defn get-classpath [project-root {:keys [cache-root version extra-cli-deps] :as config}]
  (let [cp-file
        (io/file project-root cache-root "classpath.edn")

        use-aot
        (not (false? (get config :aot true)))

        shadow-artifact
        (-> ['thheller/shadow-cljs version]
            (cond->
              use-aot
              (conj :classifier "aot")))

        classpath-config
        (-> config
            (update :dependencies into extra-cli-deps)
            (update :dependencies drop-unwanted-deps)
            (update :dependencies add-exclusions)
            (update :dependencies #(into [shadow-artifact] %)))

        cached-classpath-data
        (when (.exists cp-file)
          (-> (slurp cp-file)
              (read-string)))

        {:keys [files] :as classpath-data}
        (if (and (not (true? (:no-classpath-cache config)))
                 cached-classpath-data
                 (not (modified-dependencies? cached-classpath-data classpath-config)))
          cached-classpath-data
          (let [data (resolve-deps classpath-config)]
            (io/make-parents cp-file)
            (when (not (true? (:no-classpath-cache config)))
              (spit cp-file (pr-str data)))
            data))]

    ;; if something in the ~/.m2 directory is deleted we need to re-fetch it
    ;; otherwise we end up with weird errors at runtime
    (if (every? #(.exists %) (map io/file files))
      classpath-data
      ;; if anything is missing delete the classpath.edn and start over
      (do (.delete cp-file)
          (println "WARN: missing dependencies, reconstructing classpath.")
          (recur project-root config)))))

(def config-defaults
  {:cache-root ".shadow-cljs"
   :dependencies []})

(comment
  (get-classpath
    (io/file "target")
    '{:cache-root "fake-cache-root"
      :version "2.4.33"
      :no-classpath-cache true
      :dependencies []}))

(defn parse-cli-dep [dep-str]
  (let [[sym ver] (str/split dep-str #":")]
    [(symbol sym) ver]))

(defn extract-cli-deps [args]
  (loop [[head & tail] args
         cli-deps []]
    (cond
      (nil? head)
      cli-deps

      (or (= "-d" head)
          (= "--dependency" head))
      (let [coord
            (-> (first tail)
                (parse-cli-dep))]
        (recur
          (rest tail)
          (conj cli-deps coord)))

      :else
      (recur tail cli-deps))))

;; will the called by the Main.class
(defn -main [& args]
  (let [^DynamicClassLoader dyncl
        (-> (Thread/currentThread)
            (.getContextClassLoader))

        project-root
        (-> (io/file ".")
            (.getCanonicalFile))

        project-config-file
        (io/file project-root "shadow-cljs.edn")

        package-json-file
        (io/file project-root "node_modules" "shadow-cljs" "package.json")

        project-config
        (-> (slurp project-config-file)
            (read-string))

        version
        (or (:version project-config)
            (System/getProperty "shadow.cljs.jar-version")
            (when (.exists package-json-file)
              (-> (slurp package-json-file)
                  (json/read-str)
                  (get "jar-version")))
            (throw (ex-info "could not found which shadow-cljs version to use." {})))

        ;; shadow-cljs run something.foo deps-only
        ;; is not actually calling our deps-only
        ;; so we only check args that come before run or --
        cli-args
        (take-while
          #(and (not= "--" %)
                (not= "run" %)
                (not= "clj-run" %))
          args)

        {:keys [source-paths] :as project-config}
        (merge
          config-defaults
          {:version version}
          project-config
          {:extra-cli-deps (extract-cli-deps cli-args)})

        {:keys [files resolved-deps] :as result}
        (get-classpath project-root project-config)

        classpath-files
        (->> (concat source-paths files)
             (map io/file)
             (into []))]

    (cond
      (some #(= "deps-only" %) cli-args)
      nil

      (some #(= "deps-tree" %) cli-args)
      (tdeps/print-tree resolved-deps)

      :else
      (do (doseq [file classpath-files]
            ;; (println "classpath:" (.getAbsolutePath file))
            (let [url (-> file .toURI .toURL)]
              (.addURL dyncl url)))

          (let [cp-string
                (->> classpath-files
                     (map #(.getAbsolutePath ^File %))
                     (str/join File/pathSeparator))]

            (System/setProperty "shadow.class.path" cp-string))

          (require 'shadow.cljs.devtools.cli)
          (apply (find-var 'shadow.cljs.devtools.cli/-main) args))
      )))

