(ns demo.bootstrap-script
  (:require
    [cljs.js :as cljs]
    [cljs.env :as env]
    [shadow.cljs.bootstrap.node :as boot]))

(defn print-result [{:keys [error value] :as result}]
  (prn [:result result]))

(def code "(prn ::foo) (+ 1 2)")

(defonce compile-state-ref (env/default-compiler-env))

(defn compile-it []
  (cljs/eval-str
    compile-state-ref
    code
    "[test]"
    {:eval cljs/js-eval
     :load (partial boot/load compile-state-ref)}
    print-result))

(defn main [& args]
  (boot/init compile-state-ref {} compile-it))
