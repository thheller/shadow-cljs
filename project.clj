(defproject thheller/shadow-devtools "0.1.89"
  :description "CLJS development tools"
  :url "https://github.com/thheller/shadow-devtools"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}


  :dependencies
  [[org.clojure/clojure "1.9.0-alpha15" :scope "provided"]
   [org.clojure/clojurescript "1.9.473"]
   ;; [org.clojure/tools.nrepl "0.2.12" :scope "provided"]
   [org.clojure/data.json "0.2.6"]
   [org.clojure/core.async "0.3.442"]

   [thheller/shadow-build "1.0.280"]
   [thheller/shadow-util "0.4.0"]

   [aleph "0.4.3"]
   [hiccup "1.0.5"]]

  :source-paths
  ["src/main"]

  :test-paths
  ["src/test"]

  :java-source-paths
  ["src/main"]

  :profiles
  {:dev
   {:source-paths
    ["src/dev"]

    :dependencies
    [#_ [org.clojure/clojure "1.9.0-master-SNAPSHOT"]
     [org.clojure/tools.namespace "0.2.11"]]
    }})
