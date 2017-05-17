(ns shadow.cljs.ns-form-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.ns-form :as ns-form]
            [cljs.analyzer :as a]
            ))

(def ns-env
  (assoc-in (a/empty-env) [:ns :name] 'cljs.user))

(defn cljs-parse-ns [ns-env form]
  (binding [a/*cljs-ns* 'cljs.user
            a/*analyze-deps* false
            a/*load-macros* false]
    (a/analyze ns-env form)))

(deftest test-parse-ns
  (let [test
        '(ns something
           #_ "doc before meta"
           #_ {:some :meta}
           #_ (:refer-clojure :exclude (whatever) :rename {assoc cossa + plus})
           #_ (:use-macros [macro-use :only (that-one)])
           (:require-macros [that.macro :as m :refer (baz)])
           (:reuire only-symbol
                     [some.ns :as alias :refer (foo x) :refer-macros (a-macro-from-some-ns) :rename {foo bar}]
                     [another.ns :as x :include-macros true]
                     #_ :reload-all)
           #_(:use [something.fancy :only [everything] :rename {everything nothing}])
           #_(:import [goog.ui SomeElement OtherElement]
               a.fully-qualified.Name))


        a (ns-form/parse-ns test)
        b (-> (cljs-parse-ns ns-env test)
              (dissoc :env :form))

        check
        (fn [x]
          (-> x (select-keys [:renames :rename-macros]) pprint))]

    (pprint test)

    (check a)
    (check b)

    (is (= (:name a) (:name b)))
    ;; cljs doesn't add cljs.core here but some time later
    (is (= (dissoc (:requires a) 'cljs.core)
           (:requires b)))
    (is (= (:require-macros a) (:require-macros b)))
    (is (= (:uses a) (or (:uses b) {}))) ;; CLJS has a bug that leaves :uses as nil if the only refered var was renamed
    (is (= (:use-macros a) (:use-macros b)))
    (is (= (:imports a) (:imports b)))
    (is (= (:renames a) (:renames b)))
    (is (= (:rename-macros a) (:rename-macros b)))
    (comment
      ;; cljs actually drops the docstring if separate from meta
      (is (= (meta (:name a))
             (meta (:name b)))))))
