(ns shadow.test.workspaces
  (:require
    [nubank.workspaces.core :as ws]))

(defn start [])

(defn stop [done]
  (done))

(defn ^:export init []
  ;; FIXME: connect websocket that acts as a unload signal
  ;; when page is not open by anyone the build doesn't need to be running

  (ws/mount))
