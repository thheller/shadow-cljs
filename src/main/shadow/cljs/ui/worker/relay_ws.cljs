(ns shadow.cljs.ui.worker.relay-ws
  (:require
    [shadow.experiments.grove.worker :as sw]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.worker.env :as env]
    [clojure.string :as str]))

(defonce rpc-id-seq (atom 0))
(defonce rpc-ref (atom {}))

(defmulti handle-msg (fn [env msg] (:op msg)) :default ::default)

(defmethod handle-msg ::default [env msg]
  (js/console.warn "unhandled websocket msg" msg env)
  {})

(defmethod handle-msg :welcome
  [{:keys [db] ::keys [on-welcome] :as env} {:keys [client-id]}]

  ;; FIXME: call this via fx
  (on-welcome)

  {:db
   (assoc db ::m/tool-id client-id ::m/relay-ws-connected true)

   :ws-send
   [{:op :request-clients
     :notify true
     :query [:eq :type :runtime]}]})

(sw/reg-event-fx env/app-ref ::m/relay-ws
  []
  (fn [env {:keys [op] :as msg}]
    ;; (js/console.log ::m/relay-ws op msg)
    (handle-msg env msg)))

(defn cast! [{::keys [ws-ref] ::sw/keys [transit-str] :as env} msg]
  ;; (js/console.log "ws-send" msg)
  (.send @ws-ref (transit-str msg)))

(defn call! [env msg callback-map]
  {:pre [(map? msg)
         (map? callback-map)]}
  (let [mid (swap! rpc-id-seq inc)]
    (swap! rpc-ref assoc mid {:msg msg
                              :callback-map callback-map})
    (cast! env (assoc msg :call-id mid))))

(sw/reg-fx env/app-ref :ws-send
  (fn [{::keys [ws-ref] ::sw/keys [transit-str] :as env} messages]
    (let [socket @ws-ref]
      (doseq [msg messages]
        (.send socket (transit-str msg))))))

(defn init [app-ref on-welcome]
  (let [socket (js/WebSocket.
                 (str (str/replace js/self.location.protocol "http" "ws")
                      "//" js/self.location.host
                      "/api/remote-relay"
                      js/self.location.search))
        ws-ref (atom socket)]

    (swap! app-ref assoc
      ::ws-ref ws-ref
      ::socket socket
      ::on-welcome
      (fn []
        (cast! @app-ref {:op :hello
                         :client-info {:type :shadow-cljs-ui}})
        (on-welcome)))

    (let [{::sw/keys [^function transit-read]} @app-ref]
      (.addEventListener socket "message"
        (fn [e]
          (let [{:keys [call-id op] :as msg} (transit-read (.-data e))]
            (cond
              call-id
              (let [{:keys [callback-map] :as call-data} (get @rpc-ref call-id)
                    tx-data (get callback-map op)]
                (if-not tx-data
                  (js/console.warn "received rpc reply without handler" op msg call-data)
                  (sw/tx* @env/app-ref (conj tx-data msg))))

              (= :ping op)
              (cast! @app-ref {:op :pong})

              :else
              (sw/tx* @env/app-ref [::m/relay-ws msg]))))))

    (.addEventListener socket "open"
      (fn [e]
        ;; (js/console.log "tool-open" e socket)
        ))

    (.addEventListener socket "close"
      (fn [e]
        (js/console.log "tool-close" e)))

    (.addEventListener socket "error"
      (fn [e]
        (js/console.warn "tool-error" e)))))
