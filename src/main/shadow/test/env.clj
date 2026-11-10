(ns shadow.test.env
  (:require
    [cljs.env :as env]
    [cljs.analyzer :as ana]
    ))

(defmacro get-test-data []
  (reduce
    (fn [m {:keys [name defs] :as the-ns}]
      (let [{:syms [cljs-test-once-fixtures cljs-test-each-fixtures]}
            defs

            ;; set by targets that support it (eg. :browser-test) via shadow.build.test-util/update-test-fingerprints
            ;; changes when the ns or any of its deps were recompiled, used to only re-run affected tests
            fingerprint
            (get-in @env/*compiler* [:shadow.build.test-util/test-fingerprints name])

            vars
            (->> (vals defs)
                 (filter :test)
                 (sort-by #(-> % :meta :line))
                 (map (fn [{:keys [name]}]
                        (list 'var name)))
                 (into []))

            ns-info
            (-> {}
                (cond->
                  cljs-test-once-fixtures
                  (assoc-in [:fixtures :once] (:name cljs-test-once-fixtures))

                  cljs-test-each-fixtures
                  (assoc-in [:fixtures :each] (:name cljs-test-each-fixtures))

                  (seq vars)
                  (assoc :vars vars)

                  (and (seq vars) fingerprint)
                  (assoc :fingerprint fingerprint)))]


        (if-not (seq ns-info)
          m
          (assoc m `(quote ~name) ns-info))))
    {}
    (vals (::ana/namespaces @env/*compiler*))))
