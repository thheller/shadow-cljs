(ns shadow.cljs.ui.websocket
  (:require
    [cljs.core.async :as async :refer (go)]
    [fulcro.client.primitives :as fp]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.env :as env]
    [shadow.cljs.ui.transactions :as tx]))

;; FIXME: these should probably be pre-processed here
;; doing all the work in a TX means we can't easily push it to a worker
;; this might get a whole bunch of messages all the time
;; don't want it to lag the UI
(defn process-worker-broadcast [rc msg]
  (fp/transact! rc [(tx/process-worker-broadcast msg)]))

(defn process-supervisor [rc msg]
  (fp/transact! rc [(tx/process-supervisor msg)]))

(defn process-ws-subscription [rc {::m/keys [topic] :as msg}]
  (let [topic-id (if (vector? topic) (first topic) topic)]
    (case topic-id
      ::m/supervisor
      (process-supervisor rc msg)

      ::m/worker-broadcast
      (process-worker-broadcast rc msg)

      (js/console.warn ::unknown-subscription msg))))

(defn process-tool-msg [rc {::m/keys [tool-msg] :as msg}]
  (fp/transact! rc [(tx/process-tool-msg tool-msg)]))

(defn process-ws [rc {::m/keys [op] :as msg}]
  (case op
    ::m/sub-msg
    (process-ws-subscription rc msg)

    ::m/tool-msg
    (process-tool-msg rc msg)

    (js/console.log ::unhandled-msg msg)))

(defn open [reconciler ws-url ws-in ws-out]
  (let [ws (js/WebSocket. ws-url)]

    (.addEventListener ws "open"
      (fn [e]
        (fp/transact! reconciler [(tx/ws-open)])

        (go (loop []
              (when-some [msg (<! ws-out)]
                (.send ws msg)
                (recur)))

          (async/close! ws-in)
          (.close ws))

        (go (loop []
              (when-some [msg (<! ws-in)]
                (process-ws reconciler msg)
                (recur)))

          (async/close! ws-out)
          (.close ws))))

    (.addEventListener ws "close"
      (fn [e]
        (fp/transact! reconciler [(tx/ws-close)])
        (js/console.warn "WS-CLOSE" e)))

    (.addEventListener ws "message"
      (fn [e]
        (when-not (async/offer! ws-in (.. e -data))
          (js/console.warn "WS-IN OVERLOAD!" e))))))

(defn send [env msg]
  (let [ws-out (get-in env [:shared ::env/ws-out])]
    ;; FIXME: unlikely to happen but should still do something
    (when-not (async/offer! ws-out msg)
      (js/console.warn "WS-OUT OVERLOAD!" msg))))