(ns shadow.cljs.ui.worker.relay-ws
  (:require
    [shadow.experiments.grove.worker :as sw]
    [shadow.cljs.model :as m]
    [shadow.cljs.ui.worker.env :as env]
    [clojure.string :as str]))

(defonce rpc-id-seq (atom 0))
(defonce rpc-ref (atom {}))

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

(defn init [app-ref]
  (let [socket (js/WebSocket.
                 (str (str/replace js/self.location.protocol "http" "ws")
                      "//" js/self.location.host
                      "/api/remote-relay"
                      js/self.location.search))
        ws-ref (atom socket)]

    (swap! app-ref assoc ::ws-ref ws-ref ::socket socket)

    (let [{::sw/keys [^function transit-read]} @app-ref]
      (.addEventListener socket "message"
        (fn [e]
          (let [{:keys [call-id op] :as msg} (transit-read (.-data e))]
            (if call-id
              (let [{:keys [callback-map] :as call-data} (get @rpc-ref call-id)
                    tx-data (get callback-map op)]
                (if-not tx-data
                  (js/console.warn "received rpc reply without handler" op msg call-data)
                  (sw/tx* @env/app-ref (conj tx-data msg))))

              (sw/tx* @env/app-ref [::m/relay-ws msg]))))))

    (.addEventListener socket "open"
      (fn [e]
        ;; (js/console.log "tool-open" e socket)
        (cast! @app-ref {:op :hello
                         :client-info {:type :shadow-cljs-ui}})))

    (.addEventListener socket "close"
      (fn [e]
        (js/console.log "tool-close" e)))

    (.addEventListener socket "error"
      (fn [e]
        (js/console.warn "tool-error" e)))))
