(ns demo.browser)

(defn ^:export start []
  (prn "foo")
  (js/console.log "browser-start"))

(defn stop []
  (js/console.log "browser-stop"))
