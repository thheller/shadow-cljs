(ns demo.selfhost
  (:require [cljs.js :as cljs]
            [shadow.bootstrap :as boot]
            [cljs.env :as env]))

(defn print-result [{:keys [error value] :as result}]
  (js/console.log "result" result)
  (js/console.log "compile-state" @boot/compile-state-ref)
  (set! (.-innerHTML (js/document.getElementById "dump")) value))


(defn compile-it []
  (cljs/eval-str
    boot/compile-state-ref
    "(ns my.user (:require [reagent.core :as r])) (map inc [1 2 3])"
    "[test]"
    {:eval
     (fn [{:keys [source cache lang name]}]
       (js/console.log "Eval" name lang {:cache (some-> cache (str) (subs 0 20))
                                         :source (some-> source (subs 0 150))})
       (js/eval source))
     :load boot/load}
    print-result))

(defn start []
  (boot/init compile-it))

(defn stop [])
