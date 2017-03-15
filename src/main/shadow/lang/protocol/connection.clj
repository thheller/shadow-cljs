(ns shadow.lang.protocol.connection
  (:require [shadow.lang.protocol :as p]))

(defn InitializeResult []
  {"capabilities"
   ;; ServerCapabilities
   {"textDocumentSync"
    2
    ;; doesn't seem to accept detailed options
    #_{"openClose" true
       "change" 1 ;; 0 = off, 1 = full, 2 = incremental
       "willSave" false
       "willSaveWaitUntil" false
       "save"
       {"includeText" true}}

    "hoverProvider"
    false

    "documentHighlightProvider"
    false

    "documentSymbolProvider"
    false

    "workspaceSymbolProvider"
    false

    "completionProvider"
    ;; CompletionOptions
    {"resolveProvider" true
     "triggerCharacters" ["("]}}})

(defmethod p/handle-call "initialize"
  [client-state method params]
  (let [{:keys [processId capabilities]} params]
    (-> client-state
        (assoc :process-id processId
          :capabilities capabilities)
        (p/call-ok (InitializeResult)))))

(defmethod p/handle-cast "$/cancelRequest"
  [client-state method {:keys [id] :as params}]
  (update client-state :cancelled conj id))

(defmethod p/handle-cast "initialized"
  [client-state _ params]
  (assoc client-state :initialized? true))

;; FIXME: protocol says this should completely terminate the server
;; but so far it doesn't launch this in the first place, so just ignore it
(defmethod p/handle-call "shutdown"
  [client-state method params]
  (-> client-state
      (p/client-reset)
      (p/call-ok nil)))

(defmethod p/handle-cast "exit"
  [client-state _ params]
  (p/client-reset client-state))


