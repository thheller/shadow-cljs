(ns demo.dummy-plugin
  (:require [clojure.tools.logging :as log]))

(def plugin
  {:requires-server true
   :depends-on []
   :start
   (fn []
     (log/warn ::start)
     ::instance)
   :stop
   (fn [instance]
     (log/warn ::stop instance)
     ::done)})