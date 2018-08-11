(defproject thheller/shadow-cljs "2.4.33"
  :description "CLJS development tools"
  :url "https://github.com/thheller/shadow-cljs"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :repositories
  {"clojars" {:url "https://clojars.org/repo"
              :sign-releases false}}

  :jvm-opts
  ~(-> ["-Dfile.encoding=UTF-8"]
     (cond->
       (-> (System/getProperty "java.version") (.startsWith "9."))
       (conj "--add-modules" "java.xml.bind")))

  :javac-options
  ["-target" "1.8"
   "-source" "1.8"]

  :dependencies
  [[org.clojure/clojure "1.9.0"]
   ;; java9, not required for java8
   ;; [javax.xml.bind/jaxb-api "2.3.0"]

   ;; [org.clojure/spec.alpha "0.1.108"]
   ;; [org.clojure/core.specs.alpha "0.1.10"]

   [org.clojure/data.json "0.2.6"]
   [org.clojure/tools.cli "0.3.7"]
   [org.clojure/tools.reader "1.3.0"]
   [nrepl "0.4.4"]

   [com.cognitect/transit-clj "0.8.309"]
   [com.cognitect/transit-cljs "0.8.256"]

   [org.clojure/core.async "0.4.474"]

   ;; hack to get the latest closure-compiler if CLJS doesn't have it
   [org.clojure/clojurescript "1.10.339" :classifier "slim"
    :exclusions
    [com.google.javascript/closure-compiler-unshaded]]

   ;; [com.google.javascript/closure-compiler-unshaded "v20180319"]
   ;;  v20180506
   [com.google.javascript/closure-compiler-unshaded "v20180805"]

   [thheller/shadow-util "0.7.0"]
   [thheller/shadow-client "1.3.2"]

   [io.undertow/undertow-core "2.0.11.Final"]

   [hiccup "1.0.5"]
   [ring/ring-core "1.6.3"
    :exclusions
    ;; used by cookie middleware which we don't use
    [clj-time]]

   [expound "0.7.1"]
   [fipp "0.6.12"]

   ;; experimental
   [hawk "0.2.11"]
   [thheller/shadow-cljsjs "0.0.15"]]

  :source-paths
  ["src/main"]

  :test-paths
  ["src/test"]

  :java-source-paths
  ["src/main"]

  :classifiers
  {:aot
   {:aot [shadow.cljs.devtools.cli
          shadow.cljs.devtools.api
          shadow.cljs.devtools.server]}}

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
     [log4j "1.2.17"]]}

   :aot
   {:aot [repl]}

   :cljs
   {:java-opts ^:replace ["-XX:-OmitStackTraceInFastThrow"]
    :dependencies
    [[cider/cider-nrepl "0.18.0"]
     ;; just so the CI build has this downloaded
     ;; and cached before compiling the test-project
     [reagent "0.8.1"]]
    :repl-options
    {:init-ns shadow.user
     :nrepl-middleware
     [shadow.cljs.devtools.server.nrepl/cljs-load-file
      shadow.cljs.devtools.server.nrepl/cljs-eval
      shadow.cljs.devtools.server.nrepl/cljs-select
      ;; required by some tools, not by shadow-cljs.
      ;; cemerick.piggieback/wrap-cljs-repl
      ]}
    :source-paths
    ["src/dev"
     "src/gen"
     "src/test"
     "test-project/src/main"]}})
