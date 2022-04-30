(defproject thheller/shadow-cljs "2.18.0"
  :description "CLJS development tools"
  :url "https://github.com/thheller/shadow-cljs"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :repositories
  {"clojars" {:url "https://clojars.org/repo"
              :sign-releases false}}

  :javac-options
  ["-target" "1.8"
   "-source" "1.8"]

  :managed-dependencies
  [[commons-codec "1.15"]
   [com.google.errorprone/error_prone_annotations "2.4.0"]
   [com.google.code.findbugs/jsr305 "3.0.2"]]

  :dependencies
  [[org.clojure/clojure "1.11.1"]

   [org.clojure/data.json "2.4.0"]
   [org.clojure/tools.cli "1.0.206"]
   [org.clojure/tools.reader "1.3.6"]

   [nrepl "0.9.0"]
   [cider/piggieback "0.5.3"
    :exclusions
    [org.clojure/clojure
     org.clojure/clojurescript
     nrepl/nrepl]]

   [com.cognitect/transit-clj "1.0.329"]
   [com.cognitect/transit-cljs "0.8.269"]

   [org.clojure/core.async "1.5.648"]

   ;; hack to get the latest closure-compiler if CLJS doesn't have it
   [org.clojure/clojurescript "1.11.4"
    :exclusions
    [com.google.javascript/closure-compiler-unshaded
     org.clojure/google-closure-library
     org.clojure/google-closure-library-third-party]]

   [com.google.javascript/closure-compiler-unshaded "v20220301"]

   [org.clojure/google-closure-library "0.0-20211011-0726fdeb"]
   [org.clojure/google-closure-library-third-party "0.0-20211011-0726fdeb"]

   [thheller/shadow-util "0.7.0"]
   [thheller/shadow-client "1.3.3"]
   [thheller/shadow-undertow "0.2.0"]
   [thheller/shadow-cljsjs "0.0.22"]

   [hiccup "1.0.5"]
   [ring/ring-core "1.9.5"
    :exclusions
    ;; used by cookie middleware which we don't use
    [clj-time]]

   [io.methvin/directory-watcher "0.15.1"]

   [expound "0.9.0"]
   [fipp "0.6.25"]

   [com.bhauman/cljs-test-display "0.1.1"]
   ]

  :source-paths
  ["src/main"]

  :test-paths
  ["src/test"]

  :java-source-paths
  ["src/main"]

  :classifiers
  {:aot
   {:aot [shadow.cljs.cli
          shadow.cljs.devtools.cli
          shadow.cljs.devtools.cli-actual
          shadow.cljs.devtools.api
          shadow.cljs.devtools.server]

    :jar-exclusions
    [#"^clojure/core"]}}

  :aliases
  {"cljs-tests-compile" ["with-profiles" "+cljs-tests" "run" "-m" "shadow.cljs.devtools.cli" "compile" "cljs-tests"]
   "cljs-tests-release" ["with-profiles" "+cljs-tests" "run" "-m" "shadow.cljs.devtools.cli" "release" "cljs-tests"]}

  :profiles
  {:provided
   {:source-paths
    ["src/ui-release"]}
   :dev
   {:source-paths
    ["src/dev"
     "src/repl"]

    :jvm-opts
    ["-XX:+UnlockDiagnosticVMOptions"
     "-XX:+DebugNonSafepoints"]

    :dependencies
    [[com.clojure-goes-fast/clj-async-profiler "0.4.1"]
     #_[org.slf4j/slf4j-log4j12 "1.7.25"]
     #_[log4j "1.2.17"]]}

   :aot
   {:aot [repl]}

   :uberjar
   {:aot [shadow.cljs.devtools.cli
          shadow.cljs.devtools.api
          shadow.cljs.devtools.server]
    :main shadow.cljs.devtools.cli}

   :cljs
   {:java-opts
    ^:replace
    ["-XX:-OmitStackTraceInFastThrow"
     "-Dclojure.core.async.go-checking=true"]

    :dependencies
    [[aysylu/loom "1.0.2"]

     ;; no proper release yet, included via source-paths below
     #_[thheller/shadow-experiments "0.0.1"]



     ;; just so the CI build has this downloaded
     ;; and cached before compiling the test-project
     [reagent "0.10.0"]
     [nubank/workspaces "1.0.15"]]
    :repl-options
    {:init-ns shadow.user
     :nrepl-middleware
     [shadow.cljs.devtools.server.nrepl/middleware]}
    :source-paths
    ["src/dev"
     "src/gen"
     "src/test"
     "test-project/src/main"
     ;; lein checkouts seems buggy af in cursive
     ;; can't be bothered to figure out how to fix it
     "../shadow-experiments/src/main"]}

   :cljs-tests
   {:source-paths
    ["../oss/clojurescript/src/test/clojure"
     "../oss/clojurescript/src/test/cljs"
     "../oss/clojurescript/src/test/self"
     "../oss/clojurescript/src/test/cljs_cp"
     "../oss/clojurescript/benchmark"
     ]}})
