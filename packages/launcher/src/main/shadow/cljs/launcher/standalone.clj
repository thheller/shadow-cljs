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

(def config-defaults
  {:cache-root ".shadow-cljs"
   :dependencies []})

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

(defn drop-unwanted-deps [lib-map]
  (apply dissoc lib-map unwanted-deps))

(defn add-exclusions [dependencies]
  (reduce-kv
    (fn [m lib {:keys [exclusions] :as data}]
      (assoc m lib (assoc data :exclusions (->> exclusions
                                                (concat unwanted-deps)
                                                (distinct)
                                                (into [])))))
    dependencies
    dependencies))

(defn modified-dependencies? [cp config]
  (or (not= (:version cp) (:version config))
      (not= (:input-deps cp) (:input-deps config))))

(defn as-qualified-symbol [dep]
  (if (qualified-symbol? dep)
    dep
    (symbol (name dep) (name dep))))

;; transform lein-style [foo/bar "1.2.3] into {foo/bar {:mvn/version "1.2.3"}}
(defn as-lib-map [deps]
  (reduce
    (fn [m [dep version & kv-pairs :as x]]
      (assoc m
        (as-qualified-symbol dep)
        (-> {:mvn/version version}
            (cond->
              (seq kv-pairs)
              (merge (apply array-map kv-pairs)))
            )))
    {}
    deps))

(defn resolve-deps [{:keys [input-deps repositories] :as config}]
  (let [resolved-deps
        (tdeps/resolve-deps
          {:deps input-deps
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

(defn clean-deps [{:keys [dependencies extra-cli-deps] :as config}]
  (let [deps
        (cond
          (vector? dependencies)
          (as-lib-map dependencies)

          (map? dependencies)
          (reduce-kv
            (fn [m k v]
              (assoc m (as-qualified-symbol k) v))
            {}
            dependencies)

          :else
          (throw (ex-info "invalid dependencies" {})))]

    (-> (apply merge deps extra-cli-deps)
        (drop-unwanted-deps)
        (add-exclusions)
        )))

(comment
  (clean-deps {:dependencies '[[reagent "0.8.1"]]})
  (clean-deps {:dependencies '{reagent {:mvn/version "0.8.1"}}}))

(defn get-classpath [project-root {:keys [extra-deps] :as extra-config}]
  (let [project-config-file
        (io/file project-root "shadow-cljs.edn")

        project-config
        (-> (slurp project-config-file)
            (read-string))

        package-json-file
        (io/file project-root "node_modules" "shadow-cljs" "package.json")

        version
        (or (:version project-config)
            (System/getProperty "shadow.cljs.jar-version")
            (when (.exists package-json-file)
              (-> (slurp package-json-file)
                  (json/read-str)
                  (get "jar-version")))
            (throw (ex-info "could not find which shadow-cljs version to use." {})))

        {:keys [cache-root] :as project-config}
        (merge
          config-defaults
          {:version version}
          project-config
          extra-config)

        cp-file
        (io/file project-root cache-root "classpath.edn")

        use-aot
        (not (false? (get project-config :aot true)))

        deps
        (-> (clean-deps project-config)
            (cond->
              (seq extra-deps)
              (merge (clean-deps {:dependencies extra-deps})))
            (assoc 'thheller/shadow-cljs
                   (-> {:mvn/version version}
                       (cond->
                         use-aot
                         (assoc :classifier "aot")))))

        classpath-config
        (assoc project-config :input-deps deps)

        cached-classpath-data
        (when (.exists cp-file)
          (-> (slurp cp-file)
              (read-string)))

        {:keys [files] :as classpath-data}
        (if (and (not (true? (:no-classpath-cache project-config)))
                 cached-classpath-data
                 (not (modified-dependencies? cached-classpath-data classpath-config)))
          cached-classpath-data
          (let [data (resolve-deps classpath-config)]
            (when-not (true? (:no-classpath-cache project-config))
              (io/make-parents cp-file)
              (spit cp-file (pr-str data)))
            data))]

    ;; if something in the ~/.m2 directory is deleted we need to re-fetch it
    ;; otherwise we end up with weird errors at runtime
    (if (every? #(.exists %) (map io/file files))
      classpath-data
      ;; if anything is missing delete the classpath.edn and start over
      (do (.delete cp-file)
          (println "WARN: missing dependencies, reconstructing classpath.")
          (recur project-root extra-config)))))

(comment
  (doseq [[dep data]
          (-> (get-classpath
                (io/file ".." "..")
                {:no-classpath-cache true})
              (:resolved-deps))]
    (println dep " --- " (:dependents data))))

(defn parse-cli-dep [dep-str]
  (let [[sym ver] (str/split dep-str #":")]
    {(symbol sym) {:mvn/version ver}}))

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

(defn add-file-to-classloader [dyncl file]
  (let [url (-> file .toURI .toURL)]
    (.addURL dyncl url)))

;; will be called by the Main.class
(defn -main [& args]
  (let [^DynamicClassLoader dyncl
        (-> (Thread/currentThread)
            (.getContextClassLoader))

        project-root
        (-> (io/file ".")
            (.getCanonicalFile))

        ;; shadow-cljs run something.foo deps-only
        ;; is not actually calling our deps-only
        ;; so we only check args that come before run or --
        cli-args
        (take-while
          #(and (not= "--" %)
                (not= "run" %)
                (not= "clj-run" %))
          args)

        extra-cli-deps
        (extract-cli-deps cli-args)

        {:keys [source-paths files resolved-deps] :as config}
        (get-classpath project-root {:extra-cli-deps extra-cli-deps})

        classpath-files
        (->> (concat source-paths files)
             (map io/file)
             (into []))

        active-deps-ref
        (atom resolved-deps)

        reload-deps-fn
        (fn reload-deps-fn [{:keys [ignore-conflicts deps] :as opts}]
          (let [{:keys [resolved-deps] :as new-config}
                (get-classpath project-root {:extra-cli-deps extra-cli-deps
                                             :extra-deps deps})

                prev-deps
                @active-deps-ref

                {:keys [conflicts new] :as result}
                (reduce-kv
                  (fn [result dep data]
                    (cond
                      (not (contains? prev-deps dep))
                      (update result :new conj dep)

                      (= (:paths data)
                         (get-in prev-deps [dep :paths]))
                      result

                      :else
                      (update result :conflicts conj {:dep dep
                                                      :before (get prev-deps dep)
                                                      :after data})))
                  {:new []
                   :conflicts []}
                  resolved-deps)]

            (if (and (seq conflicts) (not (true? ignore-conflicts)))
              result
              (let [new-deps
                    (reduce
                      (fn [deps dep]
                        (assoc deps dep (get resolved-deps dep)))
                      {}
                      new)]

                (->> (vals new-deps)
                     (mapcat :paths)
                     (map io/file)
                     (map (fn [file]
                            (System/setProperty "shadow.class.path"
                              (str (System/getProperty "shadow.class.path")
                                   File/pathSeparator
                                   (.getAbsolutePath file)))
                            (add-file-to-classloader dyncl file)))
                     (doall))

                (swap! active-deps-ref merge new-deps)

                result))))]

    (cond
      (some #(= "deps-only" %) cli-args)
      nil

      (some #(= "deps-tree" %) cli-args)
      (tdeps/print-tree resolved-deps)

      :else
      (do (doseq [file classpath-files]
            ;; (println "classpath:" (.getAbsolutePath file))
            (add-file-to-classloader dyncl file))

          (let [cp-string
                (->> classpath-files
                     (map #(.getAbsolutePath ^File %))
                     (str/join File/pathSeparator))]

            (System/setProperty "shadow.class.path" cp-string))

          (require 'shadow.cljs.devtools.cli)
          ((find-var 'shadow.cljs.devtools.cli/from-launcher) reload-deps-fn args))
      )))
