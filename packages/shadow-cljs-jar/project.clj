(defproject shadow-cljs-jar "1.3.4"

  :javac-options
  ["-target" "1.8"
   "-source" "1.8"]

  :dependencies
  [[org.clojure/clojure "1.10.1"]
   [com.cemerick/pomegranate "1.1.0"]
   [org.slf4j/slf4j-nop "1.7.30"]
   [s3-wagon-private "1.3.5"
    :exclusions
    [ch.qos.logback/logback-classic]]]

  ;; uses target/ as the root not PWD
  :uberjar-name "../bin/shadow-cljs.jar"

  :aot :all
  :main shadow.cljs.npm.deps)
