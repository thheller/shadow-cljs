(defproject example/browser-simple "0.0-SNAPSHOT"
  :description "simple browser example"
  :url "https://github.com/thheller/shadow-devtools"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies
  [[org.clojure/clojure "1.9.0-alpha14" :scope "provided"]]

  :source-paths
  ["src"]

  :profiles
  {:dev
   {:dependencies
    [[thheller/shadow-devtools "0.1.79"]]
    }})