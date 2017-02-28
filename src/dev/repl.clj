(ns repl
  (:require [shadow.devtools.server.standalone :as sys]
            [clojure.pprint :refer (pprint)]
            [clojure.spec.test :as st]
            [shadow.devtools.server.services.build :as build]
            [shadow.devtools.server.config :as config]
            [shadow.devtools.server.embedded :as devtools]
            ))

(defn start []
  (st/instrument)
  (devtools/start!)
  ;; (devtools/start-autobuild :script)
  ::started)



(defn stop []
  (st/unstrument)
  (devtools/stop!)
  ::stopped)

;; (ns-tools/set-refresh-dirs "src/main")

(defn go []
  (stop)
  ;; this somehow breaks reloading
  ;; the usual :reloading message tells me that is namespace is being reloaded
  ;; but when the new instance is launched it is still using the old one
  ;; i cannot figure out why
  ;; (ns-tools/refresh :after 'repl/start)
  (start))