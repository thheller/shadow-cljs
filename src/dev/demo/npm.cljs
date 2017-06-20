(ns demo.npm
  (:require ["fs" :as fs]))

#_ (fs/existsSync "project.clj")

(defn ^:export foo []
  "hello from cljs!")

