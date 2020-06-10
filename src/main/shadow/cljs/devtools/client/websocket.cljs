(ns shadow.cljs.devtools.client.websocket
  (:require
    [shadow.cljs.devtools.client.shared :as cljs-shared]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.cljs.js-builtins]
    [shadow.cljs.devtools.client.env :as env]))

(defn load-sources [runtime sources callback]
  (shared/call runtime
    {:op :cljs-load-sources
     :to env/worker-client-id
     :sources (into [] (map :resource-id) sources)}
    {:cljs-sources
     (fn [{:keys [sources] :as msg}]
       (callback sources))}))

(defn start [ws-url client-info]
  (if-some [{:keys [stop]} @cljs-shared/runtime-ref]
    ;; if already connected. cleanup and call restart async
    ;; need to give the websocket a chance to close
    ;; only need this to support hot-reload this code
    ;; can't use :dev/before-load-async hooks since they always run
    (do (stop)
        (js/setTimeout #(start ws-url client-info) 10))

    (let [socket
          (js/WebSocket. ws-url)

          send-fn
          (fn [msg]
            (.send socket msg))

          state-ref
          (-> (assoc client-info
                :type :runtime
                :lang :cljs
                :build-id (keyword env/build-id)
                :proc-id env/proc-id)
              (shared/init-state)
              (atom))

          runtime
          (cljs-shared/Runtime.
            state-ref
            send-fn
            #(.close socket))]

      (cljs-shared/init-runtime! runtime)

      (.addEventListener socket "message"
        (fn [e]
          ;; (js/console.log "ws-message" e)
          (shared/process runtime (cljs-shared/transit-read (.-data e)))
          ))

      (.addEventListener socket "open"
        (fn [e]
          ;; (js/console.log "ws-open" e)
          ))

      (.addEventListener socket "close"
        (fn [e]
          ;; (js/console.log "ws-close" e)
          (cljs-shared/stop-runtime! e)))

      (.addEventListener socket "error"
        (fn [e]
          (js/console.log "shadow-cljs - ws-error" e))))))
