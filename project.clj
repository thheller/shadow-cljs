(defproject thheller/shadow-cljs "1.0.20170603"
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

   [org.clojure/tools.logging "0.3.1"]
   [org.clojure/tools.cli "0.3.5"]

   [com.cognitect/transit-clj "0.8.300"
    :exclusions
    [org.msgpack/msgpack]]

   [org.clojure/core.async "0.3.443"]
   [org.clojure/tools.reader "1.0.0-RC1"]
   [org.clojure/clojurescript "1.9.562"]

   ;; hack to get the latest closure-compiler if CLJS doesn't have it
   #_[[org.clojure/clojurescript "1.9.542"
       :exclusions
       [com.google.javascript/closure-compiler-unshaded
        org.clojure/tools.reader]]

      [com.google.javascript/closure-compiler-unshaded "v20170521"]]

   [thheller/shadow-util "0.5.1"]
   [thheller/shadow-client "1.0.20170607"]

   [aleph "0.4.3"]
   [hiccup "1.0.5"]
   [ring/ring-core "1.6.1"]]

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
     [org.clojure/tools.namespace "0.2.11"]]}

   :cljs
   {:source-paths
    ["src/dev"
     "src/test"]
    :aot [shadow.cljs.devtools.api
          shadow.cljs.devtools.cli]
    :dependencies
    [[thheller/shadow-client "1.0.20170518"]]}})
