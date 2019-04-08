(ns demo.script
  (:require
    ["http" :as http]
    ["request" :as req]
    ["./cjs.js" :as cjs]
    ["which" :as which]))

(js/console.log "cjs" cjs)

(prn [:goog.global js/goog.global.setTimeout])

(req "https://www.google.com"
  (fn [error res body]
    (prn [:error error])
    (prn [:res res])
    ;; (prn [:body body])
    ))

(prn [:which (which/sync "java" #js {:nothrow true})])

(defn request-handler [req res]
  (.end res "foo"))

(defonce server-ref
  (volatile! nil))

(defn main [& args]
  (js/console.log "starting server")
  (let [server (http/createServer #(request-handler %1 %2))]

    (.listen server 3000
      (fn [err]
        (if err
          (js/console.error "server start failed")
          (js/console.info "http server running"))
        ))

    (vreset! server-ref server)
    ))

(defn start []
  (js/console.warn "start called")
  (main))

(defn stop [done]
  (js/console.warn "stop called")
  (when-some [srv @server-ref]
    (.close srv
      (fn [err]
        (js/console.log "stop completed" err)
        (done)))))

(js/console.log "__filename" js/__filename)
