(ns shadow.test.node
  {:dev/always true}
  (:require
    [shadow.test.env :as env]
    [cljs.test :as ct]
    [shadow.test :as st]
    [clojure.string :as str]))

;; FIXME: add option to not exit the node process?
(defmethod ct/report [::ct/default :end-run-tests] [m]
  (if (ct/successful? m)
    (js/process.exit 0)
    (js/process.exit 1)))

;; get-test-data is a macro so this namespace REQUIRES :dev/always hint ns so that it is always recompiled
(defn ^:dev/after-load reset-test-data! []
  (-> (env/get-test-data)
      (env/reset-test-data!)))

(defn parse-args [args]
  (reduce
    (fn [opts arg]
      (cond
        (= "--help" arg)
        (assoc opts :help true)

        (= "--list" arg)
        (assoc opts :list true)

        (str/starts-with? arg "--test=")
        (let [test-arg (subs arg 7)
              test-syms
              (->> (str/split test-arg ",")
                   (map symbol))]
          (update opts :test-syms into test-syms))

        :else
        (do (println (str "Unknown arg: " arg))
            opts)
        ))
    {:test-syms []}
    args))

(defn find-matching-test-vars [test-syms]
  ;; FIXME: should have some kind of wildcard support
  (let [test-namespaces
        (->> test-syms (filter simple-symbol?) (set))
        test-var-syms
        (->> test-syms (filter qualified-symbol?) (set))]

    (->> (env/get-test-vars)
         (filter (fn [the-var]
                   (let [{:keys [name ns]} (meta the-var)]
                     (or (contains? test-namespaces ns)
                         (contains? test-var-syms (symbol ns name))))))
         )))

(defn execute-cli [{:keys [test-syms help list] :as opts}]
  (let [test-env
        (-> (ct/empty-env)
            ;; can't think of a proper way to let CLI specify custom reporter?
            ;; :report-fn is mostly for UI purposes, CLI should be fine with default report
            #_(assoc :report-fn
                (fn [m]
                  (tap> [:test m (ct/get-current-env)])
                  (prn m))))]

    (cond
      help
      (do (println "Usage:")
          (println "  --list (list known test names)")
          (println "  --test=<ns-to-test>,<fqn-symbol-to-test> (run test for namespace or single var, separated by comma)"))

      list
      (doseq [[ns ns-info]
              (->> (env/get-tests)
                   (sort-by first))]
        (println "Namespace:" ns)
        (doseq [var (:vars ns-info)
                :let [m (meta var)]]
          (println (str "  " (:ns m) "/" (:name m))))
        (println "---------------------------------"))

      (seq test-syms)
      (let [test-vars (find-matching-test-vars test-syms)]
        (st/run-test-vars test-env test-vars))

      :else
      (st/run-all-tests test-env nil)
      )))

(defn main [& args]
  (reset-test-data!)

  (if env/UI-DRIVEN
    (js/console.log "Waiting for UI ...")
    (let [opts (parse-args args)]
      (execute-cli opts))))
