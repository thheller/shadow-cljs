(ns demo.script)

(def http (js/require "http"))

(defn request-handler [req res]
  (.end res "foo"))

(defonce server-ref
  (volatile! nil))

(defn main [& args]
  (js/console.log "starting server")
  (let [server
        (.createServer http #(request-handler %1 %2))]

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

(defn stop []
  (js/console.warn "stop called")
  (when-some [srv @server-ref]
    (.close srv
      (fn [err]
        (js/console.log "stop completed" err)))))
