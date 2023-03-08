(ns shadow.cljs.devtools.client.websocket
  (:require
    [shadow.cljs.devtools.client.env :as env]
    [shadow.cljs.devtools.client.shared :as cljs-shared]
    [shadow.remote.runtime.cljs.js-builtins]
    ))

;; FIXME: protocolize the 3 fns

(defn start
  ([runtime]
   (start js/WebSocket runtime))
  ([ws-impl runtime]
   (let [ws-url (env/get-ws-relay-url)
         socket (ws-impl. ws-url)]

     (set! socket -onmessage
       (fn [e]
         (cljs-shared/remote-msg runtime (.-data e))))

     (set! socket -onopen
       (fn [e]
         (cljs-shared/remote-open runtime e)))

     (set! socket -onclose
       (fn [e]
         (cljs-shared/remote-close runtime e ws-url)))

     (set! socket -onerror
       (fn [e]
         ;; followed by close
         (cljs-shared/remote-error runtime e)))

     socket)))

(defn send [socket msg]
  (.send socket msg))

(defn stop [socket]
  ;; chrome sometimes gets stuck websockets when waking up from sleep
  ;; at least on my macbook macos chrome, works fine in windows
  ;; these sockets don't receive messages or send them
  ;; and will eventually time out after a minute or so
  ;; at which point we no longer care about close messages as a new one will
  ;; be active and we don't want to a late onclose message to disconnect that one.

  ;; firefox also has the stuck socket but that is closed pretty much immediately on wake
  ;; it still shows an error "was interrupted while loading page" after a bit
  ;; can't seem to get rid of that one but it is from the socket we no longer care about anyways
  (set! (.-onopen socket) nil)
  (set! (.-onclose socket) nil)
  (set! (.-onmessage socket) nil)
  (set! (.-onerror socket) nil)
  (.close socket))

