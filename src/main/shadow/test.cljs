(ns shadow.test
  "cljs.test just without all those damn macros
   requires the shadow.build.cljs-hacks deftest mod which calls shadow.test/register-test"
  (:require [cljs.test :as ct]
            [shadow.test.env :as env]))

(declare test-ns-block)

(defn run-tests-block
  "Like test-vars, but returns a block for further composition and
  later execution."
  [env namespaces]

  (let [summary
        (volatile!
          {:test 0 :pass 0 :fail 0 :error 0
           :type :summary})

        merge-counters
        (fn []
          (vswap!
            summary
            (partial merge-with +)
            (:report-counters (ct/get-current-env))))]

    (-> [(fn [] (ct/set-env! env))]
        (into (->> namespaces
                   (mapcat (fn [ns]
                             (-> (test-ns-block env ns)
                                 (conj merge-counters))))))
        (conj (fn []
                (ct/report @summary)
                (ct/report {:type :end-run-tests})
                (ct/clear-env!))))))

(defn run-tests
  ([]
   (run-tests (ct/empty-env)))
  ([env]
   (run-tests env (env/get-test-namespaces)))
  ([env namespaces]
   (ct/run-block (run-tests-block env namespaces))))

(defn run-all-tests
  "Runs all tests in all namespaces; prints results.
  Optional argument is a regular expression; only namespaces with
  names matching the regular expression (with re-matches) will be
  tested."
  ([] (run-all-tests (ct/empty-env) nil))
  ([env] (run-all-tests env nil))
  ([env re]
   (run-tests env
     (->> (env/get-test-namespaces)
          (filter #(or (nil? re)
                       (re-matches re (str %))))
          (into [])))))

(defn test-all-vars-block [ns]
  (let [env (ct/get-current-env)
        {:keys [fixtures each-fixtures vars] :as test-ns}
        (env/get-test-ns-info ns)]

    (-> [(fn []
           (when (nil? env)
             (ct/set-env! (ct/empty-env)))
           (when-let [fix (:once fixtures)]
             (ct/update-current-env! [:once-fixtures] assoc ns fix))
           (when-let [fix (:each fixtures)]
             (ct/update-current-env! [:each-fixtures] assoc ns fix)))]

        (into (ct/test-vars-block
                (->> vars ;; vars is {test-name test-var}
                     (vals)
                     (sort-by #(-> % meta :line)))))
        #_(conj (fn []
                  (when (nil? env)
                    (ct/clear-env!)))))))

(defn test-all-vars
  "Calls test-vars on every var with :test metadata interned in the
  namespace, with fixtures."
  [ns]
  (ct/run-block
    (-> (test-all-vars-block ns)
        (conj (fn []
                (ct/report {:type :end-test-all-vars :ns ns}))))))

(defn test-ns-block
  "Like test-ns, but returns a block for further composition and
  later execution.  Does not clear the current env."
  ([env ns]
   {:pre [(symbol? ns)]}
   [(fn []
      (ct/set-env! env)
      (ct/do-report {:type :begin-test-ns, :ns ns})
      ;; If the namespace has a test-ns-hook function, call that:
      ;; FIXME: must turn test-ns-hook into macro so it registers itself instead of just calling a defn
      (ct/block (test-all-vars-block ns)))
    (fn []
      (ct/do-report {:type :end-test-ns, :ns ns}))]))

(defn test-ns
  "If the namespace defines a function named test-ns-hook, calls that.
  Otherwise, calls test-all-vars on the namespace.  'ns' is a
  namespace object or a symbol.

  Internally binds *report-counters* to a ref initialized to
  *initial-report-counters*.  "
  ([ns] (test-ns (ct/empty-env) ns))
  ([env ns]
   (ct/run-block
     (concat (test-ns-block env ns)
       [(fn []
          (ct/clear-env!))]))))
