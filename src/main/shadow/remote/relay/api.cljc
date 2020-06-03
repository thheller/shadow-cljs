(ns shadow.remote.relay.api)

;; FIXME: api shouldn't be dependent on core.async

(defprotocol IRelayClient
  ;; from-client is channel for messages the client wants to send
  ;; returns from-relay channel for messages the relay wants to send to the client
  (connect [relay from-client client-info]))