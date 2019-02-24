(defproject thheller/shadow-cljs "2.8.9"
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

  ;; FIXME: not actually using any 1.10 features since I don't want
  ;; not sure how to best handle this in a library situation
  ;; can't use :scope "provided" since I otherwise need to track
  ;; it manually in the npm launcher which just installs shadow-cljs
  ;; and would be missing clojure then
  :dependencies
  [[org.clojure/clojure "1.10.0"]

   [org.clojure/data.json "0.2.6"]
   [org.clojure/tools.cli "0.3.7"]
   [org.clojure/tools.reader "1.3.2"]
   [nrepl "0.6.0"]

   [com.cognitect/transit-clj "0.8.313"]
   [com.cognitect/transit-cljs "0.8.256"]

   [org.clojure/core.async "0.4.490"]

   ;; hack to get the latest closure-compiler if CLJS doesn't have it
   [org.clojure/clojurescript "1.10.520"
    :exclusions
    [com.google.javascript/closure-compiler-unshaded
     org.clojure/google-closure-library]]

   ;; [com.google.javascript/closure-compiler-unshaded "v20180319"]
   ;;  v20180506
   [com.google.javascript/closure-compiler-unshaded "v20190121"]
   [org.clojure/google-closure-library "0.0-20190213-2033d5d9"]

   [thheller/shadow-util "0.7.0"]
   [thheller/shadow-client "1.3.2"]

   [io.undertow/undertow-core "2.0.17.Final"
    :exclusions
    [org.jboss.xnio/xnio-api
     org.jboss.xnio/xnio-nio]]

   [org.jboss.xnio/xnio-api "3.6.5.Final"]
   [org.jboss.xnio/xnio-nio "3.6.5.Final"
    :exlusions [org.jboss.threads/jboss-threads]]

   [org.jboss.threads/jboss-threads "2.3.2.Final"]

   [hiccup "1.0.5"]
   [ring/ring-core "1.7.1"
    :exclusions
    ;; used by cookie middleware which we don't use
    [clj-time]]

   [expound "0.7.2"]
   [fipp "0.6.14"]

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
   [thheller/shadow-cljsjs "0.0.16"]]

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

    :dependencies
    [#_[org.slf4j/slf4j-log4j12 "1.7.25"]
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
    [[fulcrologic/fulcro "2.8.0"
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
