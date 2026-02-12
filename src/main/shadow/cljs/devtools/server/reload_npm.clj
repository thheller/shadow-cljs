(ns shadow.cljs.devtools.server.reload-npm
  "service that watches fs updates and ensures npm resources are updated
   will emit system-bus messages for inform about changed resources"
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [shadow.jvm-log :as log]
    [shadow.build.npm :as npm]
    [shadow.cljs :as-alias m]
    [shadow.debug :refer (?> ?-> ?->>)])
  (:import [java.util.concurrent Executors TimeUnit]))

(defn dissoc-all [m files]
  (reduce dissoc m files))

(defn was-modified? [{:keys [file last-modified]}]
  ;; deleted or modified
  (or (not (.exists file))
      (not= last-modified (.lastModified file))))

(defn invalidate-files [index modified-files]
  (update index :files dissoc-all modified-files))

(defn check-files! [{:keys [index-ref] :as npm} update-fn]
  ;; this only needs to check files that were already referenced in some build
  ;; new files will be discovered when resolving

  (let [{:keys [files package-json-cache] :as index}
        @index-ref

        modified-packages
        (reduce-kv
          (fn [modified package-json-file {:keys [last-modified content]}]
            (if (= last-modified (.lastModified package-json-file))
              modified
              (conj modified package-json-file)))
          #{}
          package-json-cache)

        modified-dirs
        (->> modified-packages
             (map #(get-in package-json-cache [% :content :package-dir]))
             (mapv #(.getAbsolutePath %)))

        package-ids
        (->> modified-packages
             (map #(get-in package-json-cache [% :content :package-id]))
             (set))

        modified-resources
        (when (seq modified-packages)
          (reduce-kv
            (fn [modified js-file rc]
              (let [abs-path (.getAbsolutePath js-file)]
                (if-not (some #(str/starts-with? abs-path %) modified-dirs)
                  modified
                  (conj modified rc))))
            []
            files))

        modified-files
        (into [] (map :file) modified-resources)]

    (when (seq modified-resources)

      (log/debug ::npm-update {:file-count (count files)
                               :modified-count (count modified-files)})

      ;; remove from cache
      (swap! index-ref
        (fn [index]
          (-> index
              (invalidate-files modified-files)
              (update :package-json-cache dissoc-all modified-packages)
              (update :packages (fn [packages]
                                  (reduce-kv
                                    (fn [packages package-name {:keys [package-id]}]
                                      (if (contains? package-ids package-id)
                                        (dissoc packages package-name)
                                        packages))
                                    packages
                                    packages)
                                  ))
              )))

      (let [modified-provides
            (->> modified-resources
                 (map :provides)
                 (reduce set/union #{}))]

        (update-fn {:added #{} :namespaces modified-provides})))))

(defn start [npm update-fn]
  {:pre [(npm/service? npm)
         (fn? update-fn)]}
  (let [ex (Executors/newSingleThreadScheduledExecutor)

        check-fn
        (fn []
          (try
            (check-files! npm update-fn)
            (catch Exception e
              (log/warn-ex e ::npm-check-ex))))]

    {:npm npm
     :update-fn update-fn
     :check-fn check-fn
     :ex ex
     :fut (.scheduleWithFixedDelay ex check-fn 2 2 TimeUnit/SECONDS)}))

(defn stop [{:keys [ex]}]
  (.shutdown ex))

