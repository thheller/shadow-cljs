(defproject thheller/shadow-devtools "0.1.60"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies
  [[com.cognitect/transit-cljs "0.8.239"]
   [aleph "0.4.2-alpha12"]
   [gloss "0.2.5"]
   [org.clojure/clojure "1.9.0-alpha14"]
   [org.clojure/clojurescript "1.9.473"]
   [org.clojure/core.async "0.2.395"]
   [thheller/shadow-client "1.0.186"]
   [thheller/shadow-build "1.0.248"]
   [org.clojure/java.jmx "0.3.3"]
   [org.clojure/tools.logging "0.3.1"]
   [thheller/shadow-client "1.0.186"]
   [thheller/shadow-build "1.0.248"]
   [thheller/shadow-util "0.2.0"]
   [hiccup "1.0.5"]

   [ring/ring-core "1.6.0-beta6"]
   [ring/ring-devel "1.6.0-beta6"]

   [cljsjs/react "15.4.2-2"]
   [cljsjs/react-dom "15.4.2-2"]
   ]

  :source-paths
  ["src/main"]

  :java-source-paths
  ["src/main"]

  :profiles {:dev {:source-paths
                   ["src/dev"]
                   :dependencies
                   [[org.clojure/tools.namespace "0.2.11"]]
                   ;; :repl-options {:nrepl-middleware [build/browser-dev-nrepl]}

                   }})
