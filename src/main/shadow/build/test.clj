(ns shadow.build.test
  (:require [shadow.build.api :as build-api]
            [shadow.build.node :as node]
            [shadow.build.data :as data]))

(defn setup-runner [state test-namespaces]
  (let [deps
        (into ['cljs.core 'cljs.test] test-namespaces)

        test-runner-ns
        'shadow.test-runner

        cache-key
        (System/currentTimeMillis)

        test-runner-src
        {:resource-id [::test-runner "shadow/test_runner.cljs"]
         :resource-name "shadow/test_runner.cljs"
         :output-name "shadow.test_runner.js"
         :type :cljs
         :ns test-runner-ns
         :provides #{test-runner-ns}
         :requires (into #{} deps)
         :deps deps
         :source [`(~'ns ~test-runner-ns
                     (:require [cljs.test]
                       ~@(mapv vector test-namespaces)))

                  `(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m#]
                     (if (cljs.test/successful? m#)
                       (js/process.exit 0)
                       (js/process.exit 1)
                       ))

                  `(defn ~'main []
                     (cljs.test/run-tests
                       (cljs.test/empty-env)
                       ~@(for [it test-namespaces]
                           `(quote ~it))))]
         :last-modified cache-key
         :cache-key cache-key
         :virtual true}]

    (-> state
        (data/add-source test-runner-src)
        (node/configure {:main test-runner-ns
                         :output-to "target/shadow-test-runner.js"
                         :hashbang false}))))

(comment
  ;; FIXME: turn this into generic helper functions so they work for browser tests as well

  (defn find-all-test-namespaces [state]
    (->> (get-in state [:sources])
         (vals)
         (remove :jar)
         (filter build-api/has-tests?)
         (map :ns)
         (remove #{'shadow.test-runner})
         (into [])))

  (defn make-test-runner
    ([state]
     (make-test-runner state (find-all-test-namespaces state)))
    ([state test-namespaces]
     (-> state
         (setup-test-runner test-namespaces)
         (compile)
         (flush-unoptimized))))

  (defn to-source-name [state source-name]
    (cond
      (string? source-name)
      source-name
      (symbol? source-name)
      (get-in state [:provide->source source-name])
      :else
      (throw (ex-info (format "no source for %s" source-name) {:source-name source-name}))
      ))

  (defn execute-affected-tests!
    [state source-names]
    (let [source-names
          (->> source-names
               (map #(to-source-name state %))
               (into []))

          test-namespaces
          (->> (concat source-names (build-api/find-dependents-for-names state source-names))
               (filter #(build-api/has-tests? (get-in state [:sources %])))
               (map #(get-in state [:sources % :ns]))
               (distinct)
               (into []))]

      (if (empty? test-namespaces)
        (do (util/log state {:type :info
                             :msg (format "No tests to run for: %s" (pr-str source-names))})
            state)
        (do (-> state
                (make-test-runner test-namespaces)
                (execute!))
            ;; return unmodified state, otherwise previous module information and config is lost
            state))))

  (defn execute-all-tests! [state]
    (-> state
        (make-test-runner)
        (execute!))

    ;; return unmodified state!
    state
    )

  (defn execute-all-tests-and-exit! [state]
    (let [state (-> state
                    (make-test-runner)
                    (execute!))]
      (System/exit (::exit-code state)))))

