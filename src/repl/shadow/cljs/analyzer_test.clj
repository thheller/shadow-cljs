(ns shadow.cljs.analyzer-test
  (:require [shadow.build.api :as api]
            [clojure.java.io :as io]
            [shadow.cljs.util :as util]
            [shadow.build.classpath :as cp]
            [shadow.build.npm :as npm]
            [shadow.build.data :as data]
            [cljs.env :as cljs-env]
            [cljs.analyzer :as cljs-ana]
            [cljs.analyzer :as a]
            [cljs.env :as e]
            [clojure.pprint :refer (pprint)]
            [clojure.test :refer (deftest is)]
            ))

(defn test-build []
  (let [npm
        (npm/start)

        cache-root
        (io/file "target" "test-build")

        cp
        (-> (cp/start cache-root)
            (cp/index-classpath))

        output-dir
        (io/file "target" "test-build" "out")

        log
        (util/log-collector)]

    (-> (api/init)
        (api/with-cache-dir (io/file cache-root "cache"))
        (api/with-classpath cp)
        (api/with-build-options
          {:output-dir output-dir})
        (api/with-npm npm)
        (api/with-logger log))
    ))


(defn simplify-env [_ {:keys [op] :as ast} opts]
  (let [env (:env ast)
        ast (if (= op :fn)
              (assoc ast :methods
                     (map #(simplify-env nil % opts) (:methods ast)))
              ast)]
    (-> ast
        (dissoc :env)
        (assoc :env {:context (:context env)}))))

(defn elide-children [env ast opts]
  (dissoc ast :children))

(defn to-ast
  ([form] (to-ast 'cljs.user form))
  ([ns form]
   (let [env (assoc-in (a/empty-env) [:ns :name] ns)]
     (binding [a/*passes* [elide-children simplify-env a/infer-type]]
       (a/analyze env form)))))

(deftest test-analyzer-data
  (let [test-ns
        'cljs.core

        {:keys [compiler-env] :as state}
        (-> (test-build)
            (api/configure-modules {:base {:entries [test-ns]}})
            (api/analyze-modules)
            (api/compile-sources))

        compiler-env-ref
        (atom compiler-env)

        form
        '(fn [{:as c}]
           (.render ^js c))]

    (binding [a/*cljs-ns* 'cljs.user
              a/*cljs-warnings* (assoc a/*cljs-warnings* :infer-warning true)]
      (e/with-compiler-env compiler-env-ref

        (pprint (to-ast form))

        (pprint (get-in @compiler-env-ref [::a/namespaces 'cljs.user]))))

    ))