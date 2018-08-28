(ns shadow.cljs.devtools.graph.env
  (:require [com.wsscode.pathom.connect :as pc]))

(def index-ref (atom {}))

(defmulti resolver-fn pc/resolver-dispatch)
(defmulti mutation-fn pc/mutation-dispatch)

(def add-mutation (pc/mutation-factory mutation-fn index-ref))
(def add-resolver (pc/resolver-factory resolver-fn index-ref))
