(ns demo.npm
  (:require
    ["fs" :as fs]
    [shadow.resource :as rc]
    #_["./es6" :as es6]))

(def x (rc/inline "./lib.cljs"))

(goog-define SOMETHING "foo")

(defn ^:export foo []
  #_(es6/foo)
  #js["hello from cljs!" SOMETHING js/goog.DEBUG x])

(defn ^{:export-as "testFile"} test-file [name]
  (fs/existsSync name))

(def ^:export default "hello world")

(defn ^:dev/after-load yo! []
  (js/console.log "after-load yo!"))

(js/console.log "namespace load!" js/__filename js/__dirname)