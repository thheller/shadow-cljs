(ns shadow.devtools.server.web.ws-devtools
  (:require [shadow.devtools.server.services.build :as build]
            [shadow.devtools.server.web.common :as common]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer (thread alt!! >!!)]
            [clojure.string :as str]
            [aleph.http :as http]
            [manifold.deferred :as md]
            [manifold.stream :as ms]
            [clojure.edn :as edn])
  (:import (java.util UUID)))

(defn do-in
  [{:keys [result-chan] :as state}
   {:keys [type] :as msg}]
  {:pre [(map? msg)
         (keyword? type)]}
  (case type
    :repl/result
    (do (>!! result-chan msg)
        state)

    state))

(defn do-eval-out
  [{:keys [out] :as state} msg]
  (>!! out msg)
  state)

(defn ws-loop!
  [{:keys [build] :as req} proc-id client-id socket in out]
  (let [eval-out
        (async/chan)

        build-proc
        (build/get-proc-by-id build proc-id)

        result-chan
        (build/repl-eval-connect build-proc client-id eval-out)]

    (loop [client-state
           {:app req
            :client-id client-id
            :proc-id proc-id
            :socket socket
            :in in
            :out out
            :eval-out eval-out
            :result-chan result-chan}]

      (alt!!
        eval-out
        ([msg]
          (when-not (nil? msg)
            (recur (do-eval-out client-state msg))))
        in
        ([msg]
          (when-not (nil? msg)
            ;; FIXME: send to result-chan if v is eval result
            (recur (do-in client-state msg)))
          )))

    (async/close! result-chan)))

(defn ws-start
  [{:keys [ring-request] :as req}]
  (let [{:keys [uri]}
        ring-request

        client-in
        (async/chan
          1
          (map edn/read-string))

        client-out
        (async/chan
          (async/sliding-buffer 10)
          (map pr-str))

        ;; "/ws/devtools/<build-id>/<client-id>/<client-type>"
        [_ _ _ proc-id client-id client-type :as parts]
        (str/split uri #"/")

        proc-id
        (UUID/fromString proc-id)

        ;; FIXME: none-devtools repl clients
        client-type
        (keyword client-type)]

    (-> (http/websocket-connection ring-request)
        (md/chain
          (fn [socket]
            (ms/connect socket client-in)
            (ms/connect client-out socket)
            socket))
        (md/chain
          (fn [socket]
            (thread (ws-loop! req proc-id client-id socket client-in client-out))
            ))
        (md/catch common/unacceptable))))

