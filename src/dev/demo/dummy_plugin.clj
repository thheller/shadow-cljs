(ns demo.dummy-plugin
  (:require [shadow.jvm-log :as log]))

(def plugin
  {:requires-server true
   :depends-on []
   :start
   (fn []
     (log/debug ::start)
     ::instance)
   :stop
   (fn [instance]
     (log/debug ::stop {:instance instance})
     ::done)})