(ns shadow.lang.protocol.text-document
  (:require [shadow.lang.protocol :as p]))

(defmethod p/handle-cast "textDocument/didOpen"
  [client-state _ params]
  (let [uri (get-in params ["textDocument" "uri"])]
    (update client-state :open-files conj uri)))

(defmethod p/handle-cast "textDocument/didClose"
  [client-state _ params]
  (let [uri (get-in params ["textDocument" "uri"])]
    (update client-state :open-files disj uri)))

(defmethod p/handle-cast "textDocument/didChange"
  [client-state _ params]
  client-state)

(defmethod p/handle-cast "textDocument/didSave"
  [client-state _ params]
  client-state)
