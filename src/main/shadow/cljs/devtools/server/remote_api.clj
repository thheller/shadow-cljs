(ns shadow.cljs.devtools.server.remote-api
  (:require [shadow.lang.protocol :as p]))

(defmethod p/handle-call "cljs/hello"
  [client-state method params]
  (prn [:cljs/hello method params])
  (p/call-ok client-state {"hello" "world"}))


