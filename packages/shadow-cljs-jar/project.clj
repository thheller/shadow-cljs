(defproject shadow-cljs-jar "0.1.0"
  :dependencies
  [[org.clojure/clojure "1.9.0-alpha17"]
   [com.cemerick/pomegranate "0.3.1"]]

  ;; uses target/ as the root not PWD
  :uberjar-name "../bin/shadow-cljs.jar"

  :aot :all
  :main shadow.cljs.npm.deps)
