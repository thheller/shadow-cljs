(ns shadow.test
  "cljs.test just without all those damn macros
   requires the shadow.build.cljs-hacks deftest mod which calls shadow.test/register-test"
  (:require [cljs.test :as ct]
            [shadow.test.env :as env]))

(defn test-vars-grouped-block
  "like ct/test-vars-block but more generic
   groups vars by namespace, executes fixtures"
  [vars]
  (->> vars
       (group-by #(-> % meta :ns))
       ;; more predictable test ordering
       ;; FIXME: should maybe also allow randomizing to detect tests that rely on call order
       (sort-by first)
       (mapcat (fn [[ns vars]]
                 [(fn []
                    (ct/report {:type :begin-test-ns :ns ns}))
                  ;; FIXME: this is too complex, should simplify
                  (fn []
                    (ct/block
                      (let [env (ct/get-current-env)
                            once-fixtures (get-in env [:once-fixtures ns])
                            each-fixtures (get-in env [:each-fixtures ns])]
                        (case (ct/execution-strategy once-fixtures each-fixtures)
                          :async
                          (->> vars
                               (filter (comp :test meta))
                               (mapcat (comp (partial ct/wrap-map-fixtures each-fixtures)
                                         ct/test-var-block))
                               (ct/wrap-map-fixtures once-fixtures))
                          :sync
                          (let [each-fixture-fn (ct/join-fixtures each-fixtures)]
                            [(fn []
                               ((ct/join-fixtures once-fixtures)
                                (fn []
                                  (doseq [v vars]
                                    (when-let [t (:test (meta v))]
                                      ;; (alter-meta! v update :test disable-async)
                                      (each-fixture-fn
                                        (fn []
                                          ;; (test-var v)
                                          (ct/run-block
                                            (ct/test-var-block* v (ct/disable-async t))))))))))])))))
                  (fn []
                    (ct/report {:type :end-test-ns :ns ns}))])
         )))

(defn test-ns-block
  "Like test-ns, but returns a block for further composition and
  later execution.  Does not clear the current env."
  ([ns]
   {:pre [(symbol? ns)]}
   (let [{:keys [vars] :as test-ns} (env/get-test-ns-info ns)]

     (if-not test-ns
       [(fn []
          (println (str "Namespace: " ns " not found, no tests to run.")))]
       (test-vars-grouped-block vars)))))

(defn prepare-test-run [{:keys [report-fn] :as env}]
  (let [orig-report ct/report]
    [(fn []
       (ct/set-env! (assoc env ::report-fn orig-report))

       (when report-fn
         (set! ct/report report-fn))

       ;; setup all known fixtures
       (doseq [[test-ns ns-info] (env/get-tests)
               :let [{:keys [fixtures]} ns-info]]
         (when-let [fix (:once fixtures)]
           (ct/update-current-env! [:once-fixtures] assoc test-ns fix))

         (when-let [fix (:each fixtures)]
           (ct/update-current-env! [:each-fixtures] assoc test-ns fix)))

       ;; just in case report-fn wants to know when things starts
       (ct/report {:type :begin-run-tests}))]))

(defn finish-test-run [block]
  {:pre [(vector? block)]}
  (conj block
    (fn []
      (let [{::keys [report-fn] :keys [report-counters] :as env} (ct/get-current-env)]
        (ct/report (assoc report-counters :type :summary))
        (ct/report (assoc report-counters :type :end-run-tests))
        (set! ct/report report-fn)
        ))))

;; API Fns

(defn run-test-vars
  "tests all vars grouped by namespace, expects seq of test vars, can be obtained from env"
  ([test-vars]
   (run-test-vars (ct/empty-env) test-vars))
  ([env test-vars]
   (-> (prepare-test-run env)
       (into (test-vars-grouped-block test-vars))
       (finish-test-run)
       (ct/run-block))))

(defn test-ns
  "test all vars for given namespace symbol"
  ([ns]
   (test-ns (ct/empty-env) ns))
  ([env ns]
   (-> (prepare-test-run env)
       (into (test-ns-block ns))
       (finish-test-run)
       (ct/run-block))))

(defn run-tests
  "test all vars in specified namespace symbol set"
  ([]
   (run-tests (ct/empty-env)))
  ([env]
   (run-tests env (env/get-test-namespaces)))
  ([env namespaces]
   {:pre [(set? namespaces)]}
   (-> (prepare-test-run env)
       (into (->> (env/get-test-vars)
                  (filter #(contains? namespaces (-> % meta :ns)))
                  (test-vars-grouped-block)))
       (finish-test-run)
       (ct/run-block))))

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
          (into #{})))))
