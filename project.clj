(defproject thheller/shadow-cljs "2.8.68"
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
  [[commons-codec "1.11"]
   [com.google.errorprone/error_prone_annotations "2.1.3"]
   [com.google.code.findbugs/jsr305 "3.0.2"]]

  :dependencies
  [[org.clojure/clojure "1.10.1"]

   [org.clojure/data.json "0.2.6"]
   [org.clojure/tools.cli "0.3.7"]
   [org.clojure/tools.reader "1.3.2"]

   [nrepl "0.6.0"]
   [cider/piggieback "0.4.1"]

   [com.cognitect/transit-clj "0.8.313"]
   [com.cognitect/transit-cljs "0.8.256"]

   [org.clojure/core.async "0.4.500"]

   ;; hack to get the latest closure-compiler if CLJS doesn't have it
   [org.clojure/clojurescript "1.10.520"
    :exclusions
    [com.google.javascript/closure-compiler-unshaded
     org.clojure/google-closure-library
     org.clojure/google-closure-library-third-party]]

   [com.google.javascript/closure-compiler-unshaded "v20191027"]

   [org.clojure/google-closure-library "0.0-20191016-6ae1f72f"]
   [org.clojure/google-closure-library-third-party "0.0-20191016-6ae1f72f"]

   [thheller/shadow-util "0.7.0"]
   [thheller/shadow-client "1.3.2"]

   [io.undertow/undertow-core "2.0.25.Final"
    :exclusions
    [org.jboss.xnio/xnio-api
     org.jboss.xnio/xnio-nio]]

   [org.jboss.xnio/xnio-api "3.7.3.Final"]
   [org.jboss.xnio/xnio-nio "3.7.3.Final"
    :exlusions [org.jboss.threads/jboss-threads]]

   [org.jboss.threads/jboss-threads "2.3.2.Final"]

   [hiccup "1.0.5"]
   [ring/ring-core "1.7.1"
    :exclusions
    ;; used by cookie middleware which we don't use
    [clj-time]]

   [expound "0.7.2"]
   [fipp "0.6.18"]

   [com.bhauman/cljs-test-display "0.1.1"]

   [com.wsscode/pathom "2.2.7"
    :exclusions
    [org.clojure/data.json
     fulcrologic/fulcro
     ;; org.clojure/test.check
     camel-snake-kebab]]
   ;; for pathom
   [org.clojure/test.check "0.10.0-alpha3"]

   ;; experimental
   [hawk "0.2.11"]
   [thheller/shadow-cljsjs "0.0.21"]]

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
          shadow.cljs.devtools.server]

    :jar-exclusions
    [#"^clojure/core"]}}

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
    [[com.clojure-goes-fast/clj-async-profiler "0.4.0"]
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
   {:java-opts ^:replace ["-XX:-OmitStackTraceInFastThrow"]
    :dependencies
    [[com.fulcrologic/fulcro "3.0.6"
      :exclusions
      [clojure-future-spec
       com.stuartsierra/component
       garden]]

     [fulcrologic/fulcro-inspect "2.2.5"]
     [funcool/bide "1.6.0"]
     [com.andrewmcveigh/cljs-time "0.5.2"]
     [aysylu/loom "1.0.2"]

     ;; just so the CI build has this downloaded
     ;; and cached before compiling the test-project
     [reagent "0.8.1"]
     [nubank/workspaces "1.0.4"]]
    :repl-options
    {:init-ns shadow.user
     :nrepl-middleware
     [shadow.cljs.devtools.server.nrepl/middleware]}
    :source-paths
    ["src/dev"
     "src/gen"
     "src/test"
     "test-project/src/main"]}})
