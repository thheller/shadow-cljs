(defproject shadow-cljs/launcher "2.0.0"
  :description "CLJS development tools"
  :url "https://github.com/thheller/shadow-cljs"

  :javac-options
  ["-target" "1.8"
   "-source" "1.8"]

  :dependencies
  [[org.clojure/clojure "1.9.0"]

   ;; dep overrides
   [org.slf4j/slf4j-api "1.7.25"]
   [com.google.guava/guava "25.1-jre"]

   [org.clojure/tools.deps.alpha "0.5.442"
    :exclusions
    ;; some maven dep depends on old guava version
    ;; but this would clash with closure
    ;; hoping that the newer version is compatible with the old
    ;; the old is not compatible with closure
    [com.google.guava/guava]]

   ;; for reading package.json
   [org.clojure/data.json "0.2.6"]]

  :source-paths
  ["src/main"]

  :java-source-paths
  ["src/main"]

  :profiles
  {:uberjar
   {:aot :all
    :uberjar-name "shadow-cljs-launcher-%s.jar"
    :main shadow.cljs.launcher.Main}})
