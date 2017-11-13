(ns demo.read-cond
  #?(:cljs/ssr
     (:require ["fs"])

     :cljs/browser
     (:require ["codemirror"])

     :clj
     (:require [clojure.string])))

(defn main [& args]
  (prn [:main args]))
