(defproject shadow-cljs-jar "0.1.0"
  :dependencies
  [[org.clojure/clojure "1.9.0-alpha17"]
   [com.cemerick/pomegranate "0.3.1"]]

  ;; FIX duplicate file on classpath "cljs/tools/cli.cljs" (using A)
  ;; A: ~/.config/yarn/global/node_modules/shadow-cljs/bin/shadow-cljs.jar
  ;; B: ~/.m2/repository/org/clojure/tools.cli/0.3.5/tools.cli-0.3.5.jar
  :uberjar-exclusions [#".cljs$"]

  :aot :all
  :main shadow.cljs.npm.deps)
