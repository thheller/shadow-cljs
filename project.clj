(defproject thheller/shadow-devtools "0.1.79"
  :description "CLJS development tools"
  :url "https://github.com/thheller/shadow-devtools"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies
  [[org.clojure/clojure "1.9.0-alpha14" :scope "provided"]
   [org.clojure/tools.nrepl "0.2.12" :scope "provided"]
   [org.clojure/clojurescript "1.9.473"]
   [org.clojure/core.async "0.2.395"]

   [thheller/shadow-build "1.0.265"]
   [thheller/shadow-util "0.3.0"]

   [aleph "0.4.2-alpha12"]
   [hiccup "1.0.5"]

   ]

  ;; probably no nrepl
  #_:repl-options
  #_{:nrepl-middleware
     [shadow.devtools.nrepl/inject-devtools]}

  :source-paths
  ["src/main"]

  :java-source-paths
  ["src/main"]

  :profiles
  {:dev
   {:source-paths
    ["src/dev"]

    :dependencies
    [[org.clojure/tools.namespace "0.2.11"]]
    }})
