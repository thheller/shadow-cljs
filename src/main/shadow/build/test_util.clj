(ns shadow.build.test-util
  (:require [shadow.build.data :as data]
            [shadow.build.classpath :as cp]
            [shadow.cljs.util :as util]
            [clojure.java.io :as io]))

(defn find-test-namespaces [{:keys [classpath] :as state} config]
  (let [{:keys [namespaces ns-regexp exclude test-paths] :or {ns-regexp "-test$" exclude #{}}}
        config

        ns-regexp
        (re-pattern ns-regexp)]

    (if (seq namespaces)
      namespaces
      (->> (cp/find-cljs-namespaces-in-files
             classpath
             (when (seq test-paths)
               (map io/file test-paths)))
           (filter (fn [ns]
                     (re-find ns-regexp (str ns))))
           (remove exclude)
           (sort)
           (into [])))))

(defn inject-extra-requires
  [{::keys [runner-ns test-namespaces] :as state}]
  {:pre [(symbol? runner-ns)
         (coll? test-namespaces)
         (every? symbol? test-namespaces)]}

  ;; since the runner doesn't explicitly depend on the test namespaces
  ;; it may start compiling before they actually complete
  ;; which is a problem when the runner-ns uses macros that inspect the
  ;; analyzer data to discover tests since they may still be pending
  (let [runner-rc-id (data/get-source-id-by-provide state runner-ns)]

    (-> state
        (update-in [:sources runner-rc-id] assoc :extra-requires (set test-namespaces)))))


(defn configure-common [state]
  (assoc-in state [:compiler-options :load-tests] true))

(defn- will-compile? [state rc-id]
  ;; must be called before compile. sources without output or with warnings will be (re)compiled
  (let [output (get-in state [:output rc-id])]
    (or (nil? output)
        (seq (:warnings output)))))

(defn update-test-fingerprints
  "tracks when each source was last (re)compiled and computes a fingerprint for each test namespace.
   the fingerprint changes whenever the test ns or anything it depends on (transitively) is recompiled.

   the fingerprints are stored in the compiler env so the shadow.test.env/get-test-data macro can
   pass them to the runtime, which can then decide which tests need to run again after a hot-reload.

   must be called in :resolve after all sources are resolved but before compile."
  [{::keys [test-namespaces] :keys [build-sources] :as state}]
  (let [now
        (System/currentTimeMillis)

        changed-at
        (let [prev (get state ::changed-at {})]
          (reduce
            (fn [m rc-id]
              (assoc m rc-id
                (if (or (not (contains? prev rc-id))
                        (will-compile? state rc-id))
                  now
                  (get prev rc-id))))
            {}
            build-sources))

        fingerprints
        (reduce
          (fn [m test-ns]
            (if-some [rc-id (data/get-source-id-by-provide state test-ns)]
              (let [deps (data/get-deps-for-id state #{} rc-id)]
                (assoc m test-ns (reduce max 0 (map #(get changed-at % 0) deps))))
              m))
          {}
          test-namespaces)]

    (-> state
        (assoc ::changed-at changed-at)
        (assoc-in [:compiler-env ::test-fingerprints] fingerprints))))