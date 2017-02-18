(ns demo.script)

(def http (js/require "http"))

(js/require "./foo")

(defn request-handler [req res]
  (.end res "foo"))

(defonce server-ref
  (volatile! nil))

(defn main [& args]
  (let [server
        (.createServer http request-handler)]

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
    (.close srv)))
