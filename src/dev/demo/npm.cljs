(ns demo.npm
  (:require ["fs" :as fs]
            ["./es6" :as es6]))

(defn ^:export foo []
  (es6/foo)
  "hello from cljs!")

(defn ^:export test-file [name]
  (fs/existsSync name))

