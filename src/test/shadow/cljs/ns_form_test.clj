(ns shadow.cljs.ns-form-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.ns-form :as ns-form]
            [cljs.analyzer :as a]
            [clojure.repl :as repl])
  (:import (clojure.lang ExceptionInfo)))

(def ns-env
  (assoc-in (a/empty-env) [:ns :name] 'cljs.user))

(defn cljs-parse-ns [ns-env form]
  (binding [a/*cljs-ns* 'cljs.user
            a/*analyze-deps* false
            a/*load-macros* false]
    (a/analyze ns-env form)))

(deftest test-parse-ns
  (try
    (let [test
          '(ns something
             "doc before meta"
             {:some :meta}
             (:refer-clojure :exclude (whatever) :rename {assoc cossa + plus})
             (:use-macros [macro-use :only (that-one)])
             ;; FIXME: why does cljs enforce that a :rename was :refer first? what else are you going to rename?
             (:require-macros [that.macro :as m :refer (baz) :rename {baz zab}])
             (:require
               only-symbol
               [some.ns :as alias :refer (foo x) :refer-macros (a-macro-from-some-ns) :rename {foo bar}]
               [another.ns :as x :include-macros true]
               :reload-all)
             (:use [something.fancy :only [everything] :rename {everything nothing}])
             (:import
               [goog.ui SomeElement OtherElement]
               a.fully-qualified.Name))

          test
          '(ns cljs.core
             (:require goog.math.Long
                       goog.math.Integer
                       [goog.string :as gstring]
                       [goog.object :as gobject]
                       [goog.array :as garray])
             (:import [goog.string StringBuffer]))

          a (ns-form/parse test)
          b (-> (cljs-parse-ns ns-env test)
                (dissoc :env :form))

          check
          (fn [x]
            (-> x (select-keys [:imports :renames]) pprint))]

      ;; (pprint test)
      (check a)
      (check b)
      ;; (pprint a)

      (is (= (:name a) (:name b)))
      ;; cljs doesn't add cljs.core here but some time later
      (is (= (dissoc (:requires a) 'cljs.core)
             (:requires b)))
      (is (= (dissoc (:require-macros a) 'cljs.core)
             (:require-macros b)))
      (is (= (:uses a) (:uses b)))
      (is (= (:use-macros a) (:use-macros b)))
      (is (= (:imports a) (:imports b)))
      (is (= (:renames a) (:renames b)))
      (is (= (:excludes a) (:excludes b)))
      (is (= (:rename-macros a) (:rename-macros b)))
      (is (= (:deps a) (:deps b)))
      (comment
        ;; cljs actually drops the docstring if separate from meta
        (is (= (meta (:name a))
               (meta (:name b))))))

    ;; meh, clojure.test doesn't show ex-data still ...
    (catch Exception e
      (repl/pst e))))

(deftest test-ns-alias-conflict
  (let [test
        '(ns something
           (:require
             [some.a :as a]
             [some.b :as a]))]

    (is (thrown? ExceptionInfo (ns-form/parse test)))))

(deftest test-parse-ns-npm
  (let [test
        '(ns something
           (:require
             [some.ns :as a :refer (x)]
             ["react" :as react :refer (createElement)]
             ["react-dom/server" :as rdom]
             ;; ["../../relative/is-ugly" :as y]
             )
           (:import ["react" Component]))

        ast
        (ns-form/parse test)
        ]

    (pprint ast))

  )