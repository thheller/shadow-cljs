(defproject thheller/shadow-devtools "0.1.60"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies
  [[com.cognitect/transit-cljs "0.8.239"]
   [aleph "0.4.2-alpha10"]
   [org.clojure/clojure "1.9.0-alpha14"]
   [org.clojure/clojurescript "1.9.293"]
   [org.clojure/core.async "0.2.395"]
   [thheller/shadow-client "1.0.175"]
   [thheller/shadow-build "1.0.239"]
   [hiccup "1.0.5"]
   [ring/ring-core "1.5.0"]]

  :source-paths ["src/clj"
                 "src/cljs"]

  :java-source-paths ["src/java"]

  :profiles {:dev {:source-paths ["src/dev"]
                   ;; :repl-options {:nrepl-middleware [build/browser-dev-nrepl]}

                   }})
