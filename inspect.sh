#!/bin/sh

# little utility to trace load order

lein run -m clojure.main -i inspect.clj -m shadow.cljs.devtools.cli

# java -cp target/shadow-cljs-1.0.20170518-standalone.jar clojure.main -i inspect.clj -m shadow.cljs.devtools.cli

# time java -cp target/shadow-cljs-1.0.20170518-standalone.jar clojure.main -m shadow.cljs.devtools.cli -h
# time java -cp target/shadow-cljs-1.0.20170518-standalone.jar clojure.main -m shadow.cljs.devtools.cli-fast -h
