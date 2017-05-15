(defproject shadow-cljs "0.1.0"
  :dependencies
  [[org.clojure/clojure "1.9.0-alpha16"]
   [org.clojure/tools.cli "0.3.5"]
   [org.clojure/data.json "0.2.6"]
   [com.cemerick/pomegranate "0.3.1"]]

  :aot :all
  :main shadow.cljs.cli)
