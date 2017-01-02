(ns shadow.devtools.client.app
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [shadow.object :as so]
            [shadow.util :refer (log)]
            [shadow.devtools.dump :as dump :refer (obj->dom)]
            [shadow.dom :as dom]
            [shadow.api :refer (ns-ready)])
  (:import [goog.net WebSocket]
           [goog.events EventHandler]))

(defonce config-ref
  (volatile! nil))

(defonce websocket-ref
  (volatile! nil))

(reader/register-default-tag-parser!
  (fn [tag obj]
    {:tag tag
     :obj obj}))

(defn handle-message [data]
  (js/console.log "UI-msg" data))

(defn send! [cmd]
  {:pre [(map? cmd)
         (keyword? (:type cmd))]}

  (when-let [{:keys [socket]} @websocket-ref]
    (.send socket (pr-str cmd))))

(defn repl [code]
  (send! {:type :repl-input
          :code code}))

(defn start []
  (let [socket
        (WebSocket.)

        ev
        (EventHandler.)

        url
        (str "ws://"
             js/document.location.hostname ":" js/document.location.port
             "/ws/client")]

    (vreset! websocket-ref {:socket socket :ev ev})

    (.listen ev socket js/goog.net.WebSocket.EventType.OPENED
      (fn [e]
        (js/console.warn "on-open" e)))

    (.listen ev socket js/goog.net.WebSocket.EventType.CLOSED
      (fn [e]
        (js/console.warn "on-closed" e)))

    (.listen ev socket js/goog.net.WebSocket.EventType.ERROR
      (fn [e]
        (js/console.warn "on-error" e)))

    (.listen ev socket js/goog.net.WebSocket.EventType.MESSAGE
      (fn [e]
        (let [{:keys [type] :as obj}
              (reader/read-string (.-message e))]
          (js/console.log "client-msg" obj)

          (dom/insert-first
            (dom/by-id "app")
            (dump/obj->dom obj)))))

    (.open socket url))
  (js/console.warn "app-start"))

(defn init [host-el config]
  (vreset! config-ref config)
  (start))

(defn stop []
  (when-let [{:keys [socket ev]} @websocket-ref]
    (.dispose socket)
    (.dispose ev))
  (js/console.warn "app-stop"))

(ns-ready)
