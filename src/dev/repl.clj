(ns repl
  (:require [clojure.pprint :refer (pprint)]
            [clojure.spec.test :as st]
            [shadow.cljs.devtools.embedded :as devtools]
            ))

(defn start []
  (st/instrument)
  (devtools/start! {:verbose true})
  (devtools/start-worker :script)
  ::started)

(defn stop []
  (st/unstrument)
  (devtools/stop!)
  ::stopped)

(defn repl []
  (devtools/repl :script))

;; (ns-tools/set-refresh-dirs "src/main")

(defn go []
  (stop)
  ;; this somehow breaks reloading
  ;; the usual :reloading message tells me that is namespace is being reloaded
  ;; but when the new instance is launched it is still using the old one
  ;; i cannot figure out why
  ;; (ns-tools/refresh :after 'repl/start)
  (start))