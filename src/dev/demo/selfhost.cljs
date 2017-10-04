(ns demo.selfhost
  (:require [cljs.js :as cljs]
            [shadow.bootstrap :as boot]
            [cljs.env :as env]))

(defn print-result [{:keys [error value] :as result}]
  (js/console.log "result" error value))

(defn compile-it []
  (cljs/compile-str
    boot/compile-state-ref
    "(ns my.user (:require [reagent.core :as r])) (map inc [1 2 3])"
    ""
    {:eval cljs/js-eval
     :load boot/load}
    print-result))

(defn start []
  (boot/init compile-it))

(defn stop [])
