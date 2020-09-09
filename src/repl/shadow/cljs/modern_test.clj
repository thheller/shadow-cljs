(ns shadow.cljs.modern-test
  (:require
    [clojure.test :refer (deftest)]
    [cljs.analyzer :as a]
    [cljs.compiler :as comp]
    [cljs.analyzer.api :as aa]
    [cljs.env :as e]
    [shadow.build.api :as api]
    [clojure.java.io :as io]
    [shadow.build.npm :as npm]
    [shadow.build.classpath :as cp]
    [shadow.cljs.util :as util]
    ))

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


(deftest test-defclass
  (let [test-ns
        'shadow.cljs.modern

        {:keys [compiler-env] :as state}
        (-> (test-build)
            (api/configure-modules {:base {:entries [test-ns]}})
            (api/analyze-modules)
            (api/compile-sources))

        compiler-env-ref
        (atom compiler-env)

        form
        '(shadow.cljs.modern/defclass Foo
           (extends js/Element)

           (field dummy)
           (field with-default "the-default")

           (constructor [this a b]
             (let [x (+ a b)]
               (super x))
             (set! dummy (+ a b))
             (js/console.log "foo" this a b))

           Object
           (foo [this x]
             (+ dummy x)))]

    (binding [a/*cljs-ns* test-ns]
      (e/with-compiler-env compiler-env-ref

        (let [env (assoc-in (a/empty-env) [:ns :name] test-ns)]
          (binding [a/*passes* [a/infer-type]]
            (let [ast (a/analyze env form)]

              (tap> ast)
               (comp/emit ast)
              )))))))
