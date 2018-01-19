(defproject thheller/shadow-cljs "2.0.135"
  :description "CLJS development tools"
  :url "https://github.com/thheller/shadow-cljs"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :java-opts ["--add-modules" "java.xml.bind"]

  :dependencies
  [[org.clojure/clojure "1.9.0"]

   ;; [org.clojure/spec.alpha "0.1.108"]
   ;; [org.clojure/core.specs.alpha "0.1.10"]

   [org.clojure/java.classpath "0.2.3"]
   [org.clojure/data.json "0.2.6"]

   [org.clojure/tools.logging "0.4.0"]
   [org.clojure/tools.cli "0.3.5"]
   [org.clojure/tools.nrepl "0.2.13"]
   [org.clojure/tools.reader "1.1.2"]

   [com.cognitect/transit-clj "0.8.300"]
   [com.cognitect/transit-cljs "0.8.243"]

   [org.clojure/core.async "0.4.474"]

   #_[org.clojure/clojurescript "1.9.854"]

   ;; hack to get the latest closure-compiler if CLJS doesn't have it
   [org.clojure/clojurescript "1.9.946"
    :exclusions
    [com.google.javascript/closure-compiler-unshaded]]

   [com.google.javascript/closure-compiler-unshaded "v20171023"]

   [thheller/shadow-util "0.7.0"]
   [thheller/shadow-client "1.3.2"]

   [io.undertow/undertow-core "1.4.12.Final"]
   
   [hiccup "1.0.5"]
   [ring/ring-core "1.6.3"
    :exclusions
    ;; used by cookie middleware which we don't use
    [clj-time]]

   [expound "0.4.0"]
   [fipp "0.6.12"]

   ;; experimental
   [hawk "0.2.11"]
   [thheller/shadow-cljsjs "0.0.5"]]

  :source-paths
  ["src/main"]

  :test-paths
  ["src/test"]

  :java-source-paths
  ["src/main"]

  :profiles
  {:provided
   {:source-paths
    ["src/ui-release"]}
   :dev
   {:source-paths
    ["src/dev"
     "src/repl"]

    :dependencies
    [[org.slf4j/slf4j-log4j12 "1.7.25"]
     [log4j "1.2.17"]
     [org.clojure/tools.namespace "0.2.11"]]}

   :aot
   {:aot [repl]}

   :cljs
   {:java-opts ^:replace ["--add-modules" "java.xml.bind"]
    :dependencies
    [[reagent "0.8.0-alpha2"]
     #_[funcool/promesa "1.9.0"]
     [fulcrologic/fulcro "2.1.1"]

     #_[cider/cider-nrepl "0.16.0-SNAPSHOT"]

     [metosin/spec-tools "0.5.1"]
     #_[thi.ng/geom "0.0.908"]
     #_[re-view "0.4.6"]
     #_[com.rpl/specter "1.1.0"]
     #_[com.cemerick/pomegranate "1.0.0"
        :exclusions [org.slf4j/jcl-over-slf4j]]
     [org.clojure/tools.deps.alpha "0.4.295"
      :exclusions
      [org.slf4j/jcl-over-slf4j
       org.slf4j/slf4j-nop
       ch.qos.logback/logback-classic
       commons-logging]]
     #_ [binaryage/devtools "0.9.4"]]
    :source-paths
    ["src/dev"
     "src/test"]
    #_:aot
    #_[shadow.cljs.devtools.api
       shadow.cljs.devtools.cli]}})
