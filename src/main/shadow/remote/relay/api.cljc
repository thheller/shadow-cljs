(ns shadow.remote.relay.api)

;; FIXME: api shouldn't be dependent on core.async

(defprotocol IToolRelay
  (tool-connect [relay tool-out tool-info]))

(defprotocol IRuntimeRelay
  (runtime-connect [relay runtime-out tool-info]))