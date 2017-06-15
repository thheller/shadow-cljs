(ns demo.npm
  (:require ["fs" :as fs]))

(fs/existsSync "project.clj")

(defn ^:export foo []
  "hello from cljs!")

