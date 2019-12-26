(ns shadow.cljs.devtools.server.nrepl04
  "just delegates to the default nrepl impl, no longer supporting old versions"
  (:require
    [nrepl.middleware :as middleware]
    [shadow.cljs.devtools.server.nrepl :as nrepl]))

(def middleware nrepl/middleware)

(middleware/set-descriptor!
  #'middleware
  {:requires #{"clone"}
   :expects #{#'cider.piggieback/wrap-cljs-repl}})



