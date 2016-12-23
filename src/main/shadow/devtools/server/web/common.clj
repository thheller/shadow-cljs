(ns shadow.devtools.server.web.common)

(def not-found
  {:status 404
   :headers {"content-type" "text/plain"}
   :body "Not found."})

(defn unacceptable [_]
  {:status 406 ;; not-acceptable
   :headers {"Content-Type" "text/plain"}
   :body "websocket required"})


