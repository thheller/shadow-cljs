(ns shadow.test.env
  (:require-macros [shadow.test.env]))

;; this should be how cljs.test works out of the box IMHO
;; all those macros don't compose and make writing testing utilities painful
;; (eg. you have to recompile the namespace containing the macro to pick up new tests)
;; only the macros were replaced, the functionality remains unchanged
(defonce tests-ref (atom {:namespaces {}}))

(defn reset-test-data! [test-data]
  (swap! tests-ref assoc :namespaces test-data))

(defn get-tests []
  (get @tests-ref :namespaces))

(defn get-test-vars []
  (for [[ns ns-info] (get-tests)
        var (:vars ns-info)]
    var))

(defn get-test-ns-info [ns]
  {:pre [(symbol? ns)]}
  (get-in @tests-ref [:namespaces ns]))

(defn get-test-namespaces
  "returns all the registered test namespaces and symbols
   use (get-test-ns-info the-sym) to get the details"
  []
  (-> @tests-ref (:namespaces) (keys)))

(defn get-test-count []
  (->> (for [{:keys [vars] :as test-ns} (-> @tests-ref (:namespaces) (vals))]
         (count vars))
       (reduce + 0)))