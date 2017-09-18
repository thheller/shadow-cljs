(ns demo.npm
  (:require ["fs" :as fs]))

(defn ^:export foo []
  "hello from cljs!")

(defn ^:export test-file [name]
  (fs/existsSync name))

