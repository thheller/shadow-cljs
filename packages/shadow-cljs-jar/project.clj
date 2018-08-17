(defproject shadow-cljs-jar "1.0.1"
  :dependencies
  [[org.clojure/clojure "1.9.0"]
   [com.cemerick/pomegranate "1.0.0"]
   [org.slf4j/slf4j-nop "1.7.25"]]

  ;; uses target/ as the root not PWD
  :uberjar-name "../bin/shadow-cljs.jar"

  :aot :all
  :main shadow.cljs.npm.deps)
