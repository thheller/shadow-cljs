(ns shadow.test.env)

;; this should be how cljs.test works out of the box IMHO
;; all those macros don't compose and make writing testing utilities painful
;; (eg. you have to recompile the namespace containing the macro to pick up new tests)
;; only the macros were replaced, the functionality remains unchanged
(defonce tests-ref (atom {:namespaces {}}))

(defn register-test [test-ns test-name test-var]
  ;; register by name so reloading replaces the old test
  (swap! tests-ref assoc-in [:namespaces test-ns :vars test-name] test-var))

(defn register-fixtures [test-ns type fix]
  (swap! tests-ref assoc-in [:namespaces test-ns :fixtures type] fix))

(defn get-tests []
  (get @tests-ref :namespaces))

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