(ns shadow.cljs.devtools.server.env
  (:require [clojure.java.io :as io]))

(def dependencies-modified-ref (atom false))

(defn jar-version []
  (when-let [rc (io/resource "META-INF/leiningen/thheller/shadow-cljs/project.clj")]
    (-> (slurp rc)
        (read-string)
        ;; (defproject thheller/shadow-cljs "2.0.113"
        (nth 2)
        )))

(defn restart-required? []
  @dependencies-modified-ref)
