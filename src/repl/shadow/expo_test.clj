(ns shadow.expo-test
  (:require
    [clojure.test :refer (deftest is)]
    [clojure.pprint :refer (pprint)]
    [clojure.java.io :as io]
    [clojure.data.json :as json]
    [clojure.edn :as edn]
    [shadow.cljs.devtools.api :as api]
    [shadow.expo :as expo])
  (:import [com.google.debugging.sourcemap SourceMapConsumerV3]))


;; /symbolicate
(def test-stack
  [{"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "cljs$core$ExceptionInfo", "lineNumber" 158827, "column" 19}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "cljs$core$IFn$_invoke$arity$3", "lineNumber" 158888, "column" 36}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "cljs$core$IFn$_invoke$arity$2", "lineNumber" 158884, "column" 55}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "<unknown>", "lineNumber" 186801, "column" 93}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "shadow$cljs$devtools$client$env$do_js_reload_STAR_", "lineNumber" 186823, "column" 102}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "<unknown>", "lineNumber" 186820, "column" 247}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "<unknown>", "lineNumber" 186869, "column" 94}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "shadow$cljs$devtools$client$env$do_js_reload_STAR_", "lineNumber" 186823, "column" 102}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "cljs$core$IFn$_invoke$arity$3", "lineNumber" 186882, "column" 58}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "shadow$cljs$devtools$client$react_native$do_js_reload", "lineNumber" 187030, "column" 82}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "<unknown>", "lineNumber" 187206, "column" 61}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "<unknown>", "lineNumber" 187050, "column" 113}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "fireListeners", "lineNumber" 181319, "column" 27}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "dispatchEventInternal_", "lineNumber" 181416, "column" 39}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "dispatchEvent", "lineNumber" 181231, "column" 56}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "onReadyStateChangeHelper_", "lineNumber" 186074, "column" 29}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "onReadyStateChangeEntryPoint_", "lineNumber" 186020, "column" 33}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "onReadyStateChange_", "lineNumber" 186004, "column" 39}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "dispatchEvent", "lineNumber" 19424, "column" 43}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "setReadyState", "lineNumber" 19172, "column" 27}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "__didCompleteResponse", "lineNumber" 19014, "column" 29}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "emit", "lineNumber" 19922, "column" 42}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "__callFunction", "lineNumber" 5378, "column" 49}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "<unknown>", "lineNumber" 5148, "column" 31}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "__guardSafe", "lineNumber" 5340, "column" 13}
   {"file" "http://192.168.1.13:19500/bundle.ios.js", "methodName" "callFunctionReturnFlushedQueue", "lineNumber" 5147, "column" 21}]
  )


(deftest symbolicate-test
  (let [idx
        (-> (io/file "out" "TestCRNA" "out-ios" "bundle.ios.js.idx")
            (slurp)
            (edn/read-string))]

    (prn [:rc (expo/find-entry idx 124804 #_124788)])

    (pprint
      (expo/get-mapped-stack
        (io/file "out" "TestCRNA" "out-ios")
        idx
        test-stack))
    ))

(deftest mapped-stack-test
  )