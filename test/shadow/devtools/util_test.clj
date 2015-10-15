(ns shadow.devtools.util-test
  (:use clojure.test)
  (:require [shadow.devtools.util :as util]
            [clojure.pprint :refer (pprint)]
            [shadow.cljs.build :as cljs]))

(def error-msg
  {:type :repl/result
   :ua-product :chrome
   :error "Error: 1 is not ISeqable"
   :asset-root "http://localhost:4000/js/src/"
   :stacktrace "Error: 1 is not ISeqable\n    at Object.cljs$core$seq [as seq] (http://localhost:4000/js/src/cljs/core.js:4105:8)\n    at Function.cljs.core.seq_reduce.cljs$core$IFn$_invoke$arity$3 (http://localhost:4000/js/src/cljs/core.js:7350:26)\n    at Function.cljs.core.reduce.cljs$core$IFn$_invoke$arity$3 (http://localhost:4000/js/src/cljs/core.js:7461:29)\n    at Function.cljs.core.mapv.cljs$core$IFn$_invoke$arity$2 (http://localhost:4000/js/src/cljs/core.js:17397:52)\n    at http://localhost:4000/js/src/shadow/devtools/browser.js:819:23\n    at Object.shadow$devtools$browser$repl_call [as repl_call] (http://localhost:4000/js/src/shadow/devtools/browser.js:304:106)\n    at WebSocket.<anonymous> (http://localhost:4000/js/src/shadow/devtools/browser.js:817:67)"
   })

(deftest test-parse-stacktrace
  (->> (util/parse-stacktrace error-msg)
       (pprint))
  )