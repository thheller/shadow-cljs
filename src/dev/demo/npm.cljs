(ns demo.npm
  (:require
    ["fs" :as fs]
    [shadow.resource :as rc]
    #_["./es6" :as es6]))

(def x (rc/inline "./lib.cljs"))

(defn ^:export foo []
  #_(es6/foo)
  "hello from cljs!")

(defn ^:export test-file [name]
  (fs/existsSync name))

(def ^:export default "hello world")
