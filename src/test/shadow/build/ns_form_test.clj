(ns shadow.build.ns-form-test
  (:require [clojure.test :as test :refer (deftest is)]
            [clojure.pprint :refer (pprint)]
            [shadow.build.ns-form :as ns-form]
            [cljs.analyzer :as a]
            [clojure.repl :as repl]
            [clojure.spec.alpha :as s]
            [shadow.build.api :as cljs]
            [clojure.java.io :as io]
            [cljs.compiler :as cljs-comp]
            [shadow.cljs.util :as util]
            [shadow.build.resolve :as res]
            [expound.alpha :as expound]
            )
  (:import (clojure.lang ExceptionInfo)
           (java.nio.file FileSystems Paths)))
(comment
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
                 [goog]
                 [goog.ui SomeElement OtherElement]
                 a.fully-qualified.Name))

            #_[test
               '(ns cljs.core
                  (:require goog.math.Long
                            goog.math.Integer
                            [goog.string :as gstring]
                            [goog.object :as gobject]
                            [goog.array :as garray])
                  (:import [goog.string StringBuffer]))

               test
               '(ns test.foo
                  (:import [goog.history Foo]))]

            a (ns-form/parse test)
            b (-> (cljs-parse-ns ns-env test)
                  (dissoc :env :form))

            check
            (fn [x]
              (-> x (select-keys [:imports :renames :requires :deps]) pprint))]

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
        (repl/pst e)))))

(deftest test-ns-alias-conflict
  (let [test
        '(ns something
           (:require
             [some.a :as a]
             [some.b :as a]))]

    (is (thrown? ExceptionInfo (ns-form/parse test {})))))

(deftest test-parse-with-strings
  (let [test
        '(ns something
           (:require
             [some.lib :refer (x) :rename {x Bar}]
             ["react" :as react]
             ["react-dom/server" :refer (render) :default Foo]
             ["./foo" :as f]))

        {:keys [js-deps] :as ast}
        (ns-form/parse test)]

    (is (map? js-deps))
    (is (= '{:as react} (get js-deps "react")))
    ;; (pprint ast)
    ))

(deftest test-require-macros-plus-refer-macros
  (let [test
        '(ns something
           (:require-macros [cljs.test :refer-macros (deftest)]))]

    (is (thrown? ExceptionInfo (ns-form/parse test)))

    (comment
      (try
        (let [ast (ns-form/parse test)]
          (pprint ast))
        (catch Exception e
          (expound/printer (ex-data e))
          )))))

(deftest test-require-refer-macros
  (let [test
        '(ns something
           (:require [cljs.test :refer-macros (deftest) :refer (is)]))

        {:keys [use-macros uses] :as ast}
        (ns-form/parse test)]

    (is (= '{deftest cljs.test} use-macros))
    (is (= '{is cljs.test} uses))))

(deftest test-parse-and-rewrite-string
  (let [test
        '(ns something
           (:require ["react" :as react]))

        ast
        (ns-form/parse test)

        ast-resolved
        (ns-form/rewrite-js-deps ast
          {:str->sym
           ;; this maps the namespace that required a string to an alias created elsewhere
           ;; done per ns because of relative requires
           '{something {"react" alias$react}}})]

    (is (= 'alias$react (get-in ast-resolved [:requires 'react])))
    (is (= '[goog #_ shadow.cljs_helpers cljs.core alias$react] (:deps ast-resolved)))
    ;; (pprint ast)
    ;; (pprint ast-resolved)
    ))

(deftest test-parse-and-rewrite-syms-that-should-be-strings
  (let [test
        '(ns something
           (:require [react :as react :default Foo]))

        ast
        (ns-form/parse test)

        ast-resolved
        (ns-form/rewrite-ns-aliases ast
          {:ns-aliases
           '{react alias$react}})]

    (is (= 'alias$react (get-in ast-resolved [:requires 'react])))
    (is (= '[goog #_ shadow.cljs_helpers cljs.core alias$react] (:deps ast-resolved)))
    ;; (pprint ast)
    ;; (pprint ast-resolved)
    ))

(deftest test-parse-and-with-meta-on-name
  (let [test
        '(ns ^:dev/never-load something)

        ast
        (ns-form/parse test)]

    (is (get-in ast [:meta :dev/never-load]))
    (is (-> ast :name meta :dev/never-load))
    ))

(deftest test-parse-and-rewrite-rename
  (let [test
        '(ns something
           (:require [react-dnd-html5-backend :rename {default HTML5Backend}]))

        ast
        (ns-form/parse test)

        ast-resolved
        (ns-form/rewrite-ns-aliases ast
          {:ns-aliases
           '{react-dnd-html5-backend alias$react-dnd-html5-backend}})]

    (is (= 'alias$react-dnd-html5-backend (get-in ast-resolved [:requires 'react-dnd-html5-backend])))
    (is (= '[goog #_ shadow.cljs_helpers cljs.core alias$react-dnd-html5-backend] (:deps ast-resolved)))
    (is (= 'alias$react-dnd-html5-backend/default (get-in ast-resolved [:renames 'HTML5Backend])))
    ;; (pprint ast)
    ;; (pprint ast-resolved)
    ))

(deftest test-parse-repl-require
  (let [test-ns
        '(ns cljs.user)

        test-require
        '(require '[some.ns :as a :refer (x)] :reload)

        ns-info
        (ns-form/parse test-ns)

        ns-required
        (ns-form/merge-repl-require ns-info test-require)

        ns-required-again
        (-> ns-required
            (assoc :flags {}) ;; flags should be cleared by runtime
            (ns-form/merge-repl-require test-require))]

    (is (not= ns-info ns-required))
    (is (= ns-required ns-required-again))
    (is (= 'some.ns (get-in ns-required [:uses 'x])))
    (is (= 'some.ns (get-in ns-required [:requires 'a])))
    (is (= #{:reload} (get-in ns-required [:flags :require])))
    (is (= #{:reload} (get-in ns-required-again [:flags :require])))
    ))

(deftest test-parse-repl-string-require
  (let [test-ns
        '(ns cljs.user)

        test-require
        '(require '["fs" :as fs])

        ns-info
        (ns-form/parse test-ns)

        ns-required
        (ns-form/merge-repl-require ns-info test-require)]

    (is (not= ns-info ns-required))
    (pprint ns-required)
    ))

(comment
  ;; forgot what this was about
  (deftest test-aliased-refer-macros
    (let [test-ns
          '(ns some.thing
             (:require [clojure.core.async :as async :refer-macros (go)]))

          ns-info
          (ns-form/parse test-ns)

          ns-renamed
          (ns-form/rewrite-ns-aliases ns-info
            {:ns-aliases
             '{clojure.core.async cljs.core.async}})]

      (pprint ns-info)
      (pprint ns-renamed)
      )))

;; (check ::as '[:as foo])

(comment
  (defn check [spec form]
    (s/explain spec form)
    (pprint (s/conform spec form)))

  (check ::ns-form/ns-require
    '(:require
       just.a.sym
       [goog.string :as gstr]
       [some.foo :as foo :refer (x y z) :refer-macros (foo bar) :rename {x c}]
       ["react" :as react]
       :reload)))

(comment
  (check ::ns-form/ns-import
    '(:import
       that.Class
       [another Foo Bar]
       [just.Single]
       )))

(comment
  (check ::ns-form/ns-refer-clojure
    '(:refer-clojure
       :exclude (assoc)
       :rename {conj jnoc})))

(comment
  (check ::ns-form/ns-use-macros
    '(:use-macros [macro-use :only (that-one)])))

(comment
  (check ::ns-form/ns-use
    '(:use [something.fancy :only [everything] :rename {everything nothing}])))

