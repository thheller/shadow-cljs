(defproject thheller/shadow-cljs "2.0.0-alpha13"
  :description "CLJS development tools"
  :url "https://github.com/thheller/shadow-cljs"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies
  [[org.clojure/clojure "1.9.0-alpha17"]

   ;; [org.clojure/spec.alpha "0.1.108"]
   ;; [org.clojure/core.specs.alpha "0.1.10"]

   [org.clojure/java.classpath "0.2.3"]
   [org.clojure/data.json "0.2.6"]

   [org.clojure/tools.logging "0.4.0"]
   [org.clojure/tools.cli "0.3.5"]
   [org.clojure/tools.nrepl "0.2.12"]

   [com.cognitect/transit-clj "0.8.300"
    :exclusions
    [org.msgpack/msgpack]]

   [org.clojure/core.async "0.3.443"]

   #_ [org.clojure/clojurescript "1.9.854"]

   ;; hack to get the latest closure-compiler if CLJS doesn't have it
   [org.clojure/clojurescript "1.9.908"
    :exclusions
    [com.google.javascript/closure-compiler-unshaded]]

   [com.google.javascript/closure-compiler-unshaded "v20170910"]

   [thheller/shadow-util "0.5.1"]
   [thheller/shadow-client "1.0.20170628"]

   [aleph "0.4.3"]
   [hiccup "1.0.5"]
   [ring/ring-core "1.6.1"
    :exclusions
    ;; used by cookie middleware which we don't use
    [clj-time]]

   ;; [cljs-tooling "0.2.0"]
   ;; [compliment "0.3.4"]
   ]

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
    [#_[org.clojure/clojure "1.9.0-master-SNAPSHOT"]
     [org.slf4j/slf4j-log4j12 "1.7.21"]
     [log4j "1.2.17"]
     [org.clojure/tools.namespace "0.2.11"]]}

   :cljs
   {:dependencies
    [[cljsjs/create-react-class "15.6.0-2"]
     [reagent "0.8.0-alpha1"]]
    :source-paths
    ["src/dev"
     "src/test"]
    #_:aot
    #_[shadow.cljs.devtools.api
       shadow.cljs.devtools.cli]}})
