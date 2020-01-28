(ns shadow.build.test-util
  (:require [shadow.build.data :as data]
            [shadow.build.classpath :as cp]))

(defn find-test-namespaces [{:keys [classpath] :as state} config]
  (let [{:keys [namespaces ns-regexp exclude] :or {ns-regexp "-test$" exclude #{}}}
        config]

    (if (seq namespaces)
      namespaces
      (->> (cp/get-all-resources classpath)
           (filter :file) ;; only test with files, ie. not tests in jars.
           (filter #(= :cljs (:type %)))
           (map :ns)
           (filter (fn [ns]
                     (re-find (re-pattern ns-regexp) (str ns))))
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