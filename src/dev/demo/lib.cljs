(ns demo.lib)

(js/console.log "demo.lib")

(defn hello []
  (throw (ex-info "hello" {}))
  "hello")

