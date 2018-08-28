(ns shadow.cljs.devtools.graph.util
  (:import [clojure.lang IFn]))

(defrecord TxDefinition [tx-id]
  IFn
  (invoke [this]
    (list tx-id {}))
  (invoke [this params]
    (list tx-id params)))

(defn tx-def? [x]
  (instance? TxDefinition x))

(defmacro deftx [sym & ignored] ;; FIXME: add spec support for params
  (let [fq-sym (symbol (str *ns*) (name sym))]
    `(def ~sym (TxDefinition. (quote ~fq-sym)))))
