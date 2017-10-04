(ns demo.selfhost
  (:require [cljs.js :as cljs]
            [shadow.bootstrap :as boot]))

(boot/init
  (fn []
    (cljs/compile-str
      (cljs/empty-state)
      "(ns my.user) (map inc [1 2 3])"
      ""
      {:eval cljs/js-eval
       :load boot/load}
      identity)))
