(ns shadow.cljs.devtools.graph.util
  (:require-macros [shadow.cljs.devtools.graph.util]))

(defrecord TxDefinition [tx-id]
  IFn
  (-invoke [this]
    (list tx-id {}))
  (-invoke [this args]
    (list tx-id args)))

(defn tx-def? [x]
  (instance? TxDefinition x))

(defn deftx
  "I absolutely hate all the quoting required for transaction symbols
   the macro just makes them callable functions that construct the transaction data"
  [tx-sym])
