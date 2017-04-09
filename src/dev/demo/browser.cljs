(ns demo.browser)

(js/console.log "foo")
(js/console.log "demo.browser")

(defn ^:export start []
  (prn "foo")
  (js/console.log "browser-start"))

(defn stop []
  (js/console.log "browser-stop"))

