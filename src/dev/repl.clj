(ns repl
  (:require [shadow.devtools.server.livetest :as livetest]
            [clojure.pprint :refer (pprint)]
            [clojure.spec.test :as st]
            [clojure.tools.namespace.repl :as ns-tools]))

(defonce instance-ref
  (volatile! nil))

(defn app []
  (:app @instance-ref))

(defn start []
  (st/instrument)

  (let [livetest (livetest/start)]
    (vreset! instance-ref livetest))
  :started)

(defn stop []
  (st/unstrument)

  (when-let [inst @instance-ref]
    (livetest/stop inst)
    (vreset! instance-ref nil))
  :stopped)

(defn go []
  (stop)
  ;; THIS LOADS EVERY FUCKING CLJ FILE ON THE CLASSPATH, NOT COOL!
  ;;  (ns-tools/refresh :after 'repl/start)
  (start))