(ns shadow.devtools.server.web.ws-frontend
  (:require [shadow.devtools.server.services.build :as build]
            [shadow.devtools.server.web.common :as common]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer (go alt! >! <!)]
            [aleph.http :as http]
            [clojure.edn :as edn]
            [manifold.deferred :as md]
            [manifold.stream :as ms]
            [clojure.string :as str]))

(defn ws-loop
  [{:keys [build] :as req}
   socket
   ws-in
   ws-out]
  ;; not a repl eval client

  (try
    (let [repl-in
          (async/chan)

          build-id
          :self

          build-proc
          (-> (build/find-proc-by-build-id build build-id)
              (build/watch ws-out))

          repl-result
          (build/repl-client-connect build-proc :dummy repl-in)]

      (go (loop []
            (alt!
              repl-result
              ([msg]
                (when-not (nil? msg)
                  (>! ws-out {:type :repl-result
                              :value msg})
                  (recur)))

              ws-in
              ([msg]
                (when-not (nil? msg)
                  (case (:type msg)
                    :repl-input
                    (>! repl-in (:code msg))

                    ;; default
                    nil)

                  (recur)
                  ))))

          (async/close! repl-in)
          (async/close! ws-in)
          (async/close! ws-out)
          ))

    (catch Exception e
      (log/warn e "ui-loop-error"))))

(defn ws-start
  [{:keys [ring-request] :as req}]
  (let [client-in
        (async/chan
          (async/sliding-buffer 10)
          (map edn/read-string))

        client-out
        (async/chan
          (async/sliding-buffer 10)
          (map pr-str))

        [_ :as parts]
        (-> (:uri ring-request)
            (str/split #"\/"))]


    (-> (http/websocket-connection ring-request)
        (md/chain
          (fn [socket]
            (ms/connect socket client-in)
            (ms/connect client-out socket)
            socket))
        (md/chain
          (fn [socket]
            (ws-loop req socket client-in client-out)
            ))
        (md/catch common/unacceptable))))


