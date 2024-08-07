(ns shadow.cljs.ui.db.relay-ws
  (:require
    [shadow.grove :as sg]
    [shadow.grove.events :as ev]
    [shadow.cljs :as-alias m]
    [clojure.string :as str]))

(defonce rpc-id-seq (atom 0))
(defonce rpc-ref (atom {}))
(defonce rt-ref (sg/get-runtime ::m/ui))

(defn relay-welcome
  {::ev/handle ::welcome}
  [env {:keys [client-id]}]

  ;; FIXME: call this via fx
  (let [on-welcome (::on-welcome @(::sg/runtime-ref env))]
    (on-welcome))

  (-> env
      (update ::m/ui assoc ::m/tool-id client-id ::m/relay-ws-connected true)
      (ev/queue-fx :relay-send
        [{:op :request-clients
          :notify true
          :query [:eq :type :runtime]}])))

(defn ui-options
  {::ev/handle ::m/ui-options}
  [env {:keys [ui-options]}]
  (assoc-in env [::m/ui ::m/ui-options] ui-options))

(defn relay-ws-close
  {::ev/handle ::m/relay-ws-close}
  [env _]
  (assoc-in env [::m/ui ::m/relay-ws-connected] false))

(defn cast! [rt-ref msg]
  (sg/dev-only
    (when (not= :pong (:op msg))
      (sg/dev-log "WS-SEND" (:op msg) msg)))

  (let [{::keys [ws-ref] ::sg/keys [transit-str]} @rt-ref]
    (.send @ws-ref (transit-str msg))))

(defn call! [rt-ref msg result-data]
  {:pre [(map? msg)
         (or (fn? result-data)
             (and (map? result-data)
                  (keyword? (:e result-data))))]}
  (let [mid (swap! rpc-id-seq inc)]
    (swap! rpc-ref assoc mid {:msg msg
                              :result-data result-data})
    (cast! rt-ref (assoc msg :call-id mid))))

(ev/reg-fx rt-ref :relay-send
  (fn [env messages]
    (let [rt-ref (::sg/runtime-ref env)]
      (doseq [msg messages
              :when msg]
        (if-some [result (::result msg)]
          (call! rt-ref (dissoc msg ::result) result)
          (cast! rt-ref msg))))))

(defn init [rt-ref server-token on-welcome]
  (let [socket (js/WebSocket.
                 (str (str/replace js/self.location.protocol "http" "ws")
                      "//" js/self.location.host
                      "/api/remote-relay"
                      "?id=shadow-cljs-ui"
                      "&server-token=" server-token
                      ))
        ws-ref (atom socket)]

    (swap! rt-ref assoc
      ::ws-ref ws-ref
      ::socket socket
      ::server-token server-token
      ::on-welcome
      (fn []
        (cast! rt-ref {:op :hello
                       :client-info {:type :shadow-cljs-ui}})
        (on-welcome)))

    (let [^function transit-read (::sg/transit-read @rt-ref)]
      (.addEventListener socket "message"
        (fn [e]
          (let [{:keys [call-id op] :as msg} (transit-read (.-data e))]

            (cond
              call-id
              (let [{:keys [result-data] :as call-data} (get @rpc-ref call-id)]
                (if (fn? result-data)
                  (result-data msg)
                  (sg/run-tx! rt-ref (assoc result-data :call-result msg))))

              (= :ping op)
              (cast! rt-ref {:op :pong})

              :else
              (sg/run-tx! rt-ref
                (if (qualified-keyword? op)
                  (assoc msg :e op)
                  ;; meh, probably shouldn't have used unqualified keywords in shadow.remote?
                  ;; not doing the previous ::m/relay-msg multi method since that sucks in grove event log
                  (assoc msg :e (keyword "shadow.cljs.ui.db.relay-ws" (name op)))
                  )))))))

    (.addEventListener socket "open"
      (fn [e]
        ;; (js/console.log "tool-open" e socket)
        ))

    (.addEventListener socket "close"
      (fn [e]
        (sg/run-tx! rt-ref {:e ::m/relay-ws-close})
        (js/console.log "tool-close" e)))

    (.addEventListener socket "error"
      (fn [e]
        (js/console.warn "tool-error" e)))))
