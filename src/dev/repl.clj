(ns ^:skip-aot repl
  (:require [clojure.pprint :refer (pprint)]
            [shadow.cljs.devtools.embedded :as cljs]
            [shadow.cljs.devtools.standalone :as standalone]))

(defn start []
  ;; (cljs/start! {:verbose true})
  ;; (cljs/start-worker :cli)
  (standalone/start!)
  ::started)

(defn stop []
  ;; (cljs/stop!)
  (standalone/stop!)
  ::stopped)

(defn repl []
  (cljs/repl :script))

;; (ns-tools/set-refresh-dirs "src/main")

(defn go []
  (stop)
  ;; this somehow breaks reloading
  ;; the usual :reloading message tells me that is namespace is being reloaded
  ;; but when the new instance is launched it is still using the old one
  ;; i cannot figure out why
  ;; (ns-tools/refresh :after 'repl/start)
  (start))