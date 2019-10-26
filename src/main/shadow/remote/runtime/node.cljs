(ns shadow.remote.runtime.node
  (:require
    [cognitect.transit :as transit]
    ;; this will eventually replace shadow.cljs.devtools.client completely
    [shadow.cljs.devtools.client.env :as env]
    [shadow.remote.runtime.js-builtins]
    [shadow.remote.runtime.shared :as shared]
    ["ws" :as ws]))

;; FIXME: would prefer these to be non-global
;; there should only be once instance per runtime though
(def state-ref (atom {:objects {}
                      :tap-subs #{}}))

(defonce socket-ref (atom nil))

(defn send [obj]
  (let [w (transit/writer :json)
        json (transit/write w obj)]
    (when-some [socket @socket-ref]
      (.send socket json))))

(defonce tap-fn
  (fn tap-fn [obj]
    (when (some? obj)
      (let [oid (shared/register state-ref obj {:from :tap})]
        (doseq [tid (:tap-subs @state-ref)]
          (send {:op :tap :tid tid :oid oid}))
        ))))

(defn stop []
  (remove-tap tap-fn)
  (when-some [socket @socket-ref]
    (.close socket)
    (reset! socket-ref nil)))

(defn start []
  (let [ws-url
        (str (env/get-ws-url-base) "/api/runtime")

        socket
        (ws. ws-url)]

    (reset! socket-ref socket)

    (.on socket "message"
      (fn [data]
        (let [t (transit/reader :json)

              {:keys [mid tid] :as msg}
              (transit/read t data)

              reply-fn
              (fn reply-fn [res]
                (let [res (-> res
                              (cond->
                                mid
                                (assoc :mid mid)
                                tid
                                (assoc :tid tid)))]
                  (send res)))]

          (shared/process state-ref msg reply-fn))))

    (.on socket "open"
      (fn [e]
        ;; allow shared/process to send messages directly to relay
        ;; without being coupled to the implementation of exactly how
        (swap! state-ref assoc :relay-msg send)
        (add-tap tap-fn)))

    (.on socket "close"
      (fn [e]
        (stop)))

    (.on socket "error"
      (fn [e]
        (js/console.warn "tap-socket error" e)
        (stop)
        ))))

;; want things to start when this ns is in :preloads
(start)
