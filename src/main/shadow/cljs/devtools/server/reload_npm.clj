(ns shadow.cljs.devtools.server.reload-npm
  "service that watches fs updates and ensures npm resources are updated
   will emit system-bus messages for inform about changed resources"
  (:require
    [clojure.core.async :as async :refer (alt!! thread)]
    [clojure.set :as set]
    [shadow.jvm-log :as log]
    [shadow.build.npm :as npm]
    [shadow.cljs.model :as m]
    [shadow.debug :refer (?> ?-> ?->>)]
    ))

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
              (conj modified (:package-name content))))
          #{}
          package-json-cache)

        modified-resources
        (when (seq modified-packages)
          (reduce-kv
            (fn [modified js-file {:keys [package-name] :as rc}]
              (if-not (contains? modified-packages package-name)
                modified
                (conj modified rc)))
            []
            files))

        modified-files
        (into [] (map :file) modified-resources)]

    (when (seq modified-resources)

      (log/debug ::npm-update {:file-count (count files)
                               :modified-count (count modified-files)})

      ;; remove from cache
      (swap! index-ref invalidate-files modified-files)

      (let [modified-provides
            (->> modified-resources
                 (map :provides)
                 (reduce set/union #{}))]

        (update-fn {:added #{} :namespaces modified-provides})))))

(defn watch-loop [npm control-chan update-fn]
  (loop []
    (alt!!
      control-chan
      ([_] :stop)

      (async/timeout 2000)
      ([_]
       (try
         (check-files! npm update-fn)
         (catch Exception e
           (log/warn-ex e ::npm-check-ex)))
       (recur))))

  ::terminated)

(defn start [npm update-fn]
  {:pre [(npm/service? npm)
         (fn? update-fn)]}
  (let [control-chan (async/chan)]
    {:npm npm
     :control-chan control-chan
     :update-fn update-fn
     :watch-thread (thread (watch-loop npm control-chan update-fn))}))

(defn stop [{:keys [watch-thread control-chan]}]
  (async/close! control-chan)
  (async/<!! watch-thread))

