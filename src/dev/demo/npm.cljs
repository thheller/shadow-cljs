(ns demo.npm
  (:require ["fs" :as fs]
            #_ ["./es6" :as es6]))

(defn ^:export foo []
  #_ (es6/foo)
  "hello from cljs!")

(defn ^:export test-file [name]
  (fs/existsSync name))

(def ^:export default "hello world")
