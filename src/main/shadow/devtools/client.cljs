(ns shadow.devtools.client
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [shadow.object :as so]
            [shadow.util :refer (log)]
            [shadow.devtools.dump :refer (obj->dom)]
            [shadow.dom :as dom]))

(def container (dom/by-id "message-container"))

(defn process-message [body]
  (js/console.log "process-message" body)
  (let [{:keys [title data]} (reader/read-string body)
        data (reader/read-string data)
        dom (obj->dom data)]
    (dom/insert-first container [:div.value-container
                                 [:div.value-title title]
                                 dom])
    ))

(defonce websocket-url (atom nil))
(defonce websocket (atom nil))

(defn ^:export connect [url]
  (dom/reset container)
  (dom/append container [:h2 "connecting to: " url])
  (reset! websocket-url url)
  (let [socket (js/WebSocket. url)]
      (set! (.-onmessage socket) (fn [e]
                                   (process-message (-> e .-data))))
      (set! (.-onopen socket) (fn [e] (dom/append container "DEVTOOLS: connected!")))
      (set! (.-onclose socket) (fn [e]))
      (set! (.-onerror socket) (fn [e]))
      (reset! websocket socket)
      ))

(defn ^:export activate [url]
  (-> url
      (str/replace #"^http" "ws")
      (str "/" (random-uuid) "/client")
      (connect)))

(defn ^:export restart []
  (connect @websocket-url))

(defn ^:export deactivate []
  (when-let [socket @websocket]
    (.close socket)
    (reset! websocket nil)))

(defn ^:export unload []
  (deactivate)
  (doseq [[_ obj] @so/instances]
    (when (so/alive? obj)
      (so/destroy! obj))))

