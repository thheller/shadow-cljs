(ns shadow.cljs.analyzer-test
  (:require
    [clojure.java.io :as io]
    [clojure.pprint :refer (pprint)]
    [clojure.test :refer (deftest is)]
    [clojure.inspector :refer (inspect)]
    [clojure.tools.analyzer :as ana]
    [cljs.env :as cljs-env]
    [cljs.analyzer :as cljs-ana]
    [cljs.analyzer :as a]
    [cljs.env :as e]
    [cljs.analyzer.api :as ana-api]
    [shadow.build.api :as api]
    [shadow.cljs.util :as util]
    [shadow.build.classpath :as cp]
    [shadow.build.npm :as npm]
    [shadow.build.data :as data]
    [shadow.debug :refer (?> ?-> ?->>)]
    [cljs.compiler :as comp]))

(defn test-build []
  (let [npm
        (npm/start {})

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
     (binding [a/*passes* [a/infer-type]]
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

    (binding [a/*cljs-ns* 'cljs.core]
      (e/with-compiler-env compiler-env-ref

        (?> (to-ast form) :ast)
        (?> (get-in @compiler-env-ref [::a/namespaces 'cljs.core]) :cljs.core)
        ))

    ))

(deftest test-analyzer-data2
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
        '(defn foo [^function bar]
           (bar 1 2 3))]

    (binding [a/*cljs-ns* 'cljs.core
              a/*cljs-static-fns* true]
      (e/with-compiler-env compiler-env-ref

        (let [env
              (-> (a/empty-env)
                  (assoc :shadow.build/tweaks true)
                  (assoc-in [:ns :name] test-ns))
              ast
              (binding [a/*passes* [a/infer-type]]
                (a/analyze env form))]

          (?> ast :ast)
          (comp/emit ast))
        ))))

(deftest test-macroexpand
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

        (pprint (cljs-ana/macroexpand-1
                  (cljs-ana/empty-env)
                  #_'(deftype Foo [bar]
                       Object
                       (thing [this foo]))
                  '(cljs.core/extend-type Foo Object (thing ([this foo])))
                  ))

        ))

    ))

(deftest test-analyzer-deftype
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
        '(deftype Foo [bar]
           Object
           (x [y]))]

    (binding [a/*cljs-ns* 'cljs.user]
      (e/with-compiler-env compiler-env-ref
        (to-ast form)

        (pprint (get-in @compiler-env-ref [::a/namespaces 'cljs.user]))))

    ))