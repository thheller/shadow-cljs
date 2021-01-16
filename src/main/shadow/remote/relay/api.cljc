(ns shadow.remote.relay.api)

(defprotocol IRelayClient
  ;; returns extra channel that will never receive anything
  ;; only closes when the connection terminates
  ;; closing from-client will cause the loop shutdown
  ;; to-client will be closed if sending to it failed
  ;; which will also terminate the connection
  ;; connection info is a map of data, which may provide additional details
  ;; that the client cannot directly provide itself (eg. network details)
  ;; remote connections should include {:remote true}
  (connect [relay from-client to-client connection-info]))