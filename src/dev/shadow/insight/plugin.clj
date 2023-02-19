(ns shadow.insight.plugin
  (:require
    [shadow.insight.remote-ext :as insight-ext]))

(def plugin
  {:requires-server true
   :depends-on [:clj-runtime :clj-runtime-obj-support]
   :start insight-ext/start
   :stop insight-ext/stop})



