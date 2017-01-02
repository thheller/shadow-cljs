(ns shadow.devtools.server.web.ws-devtools
  "the websocket which is injected into the app, responsible for live-reload, repl-eval, etc"
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
  [{:keys [result-chan] :as client-state}]
  (loop [{:keys [eval-out
                 watch-chan
                 in
                 out]
          :as client-state}
         client-state]

    (alt!!
      eval-out
      ([msg]
        (when-not (nil? msg)
          (recur (do-eval-out client-state msg))))

      watch-chan
      ([msg]
        (when-not (nil? msg)
          (>!! out msg)
          (recur client-state)
          ))

      in
      ([msg]
        (when-not (nil? msg)
          ;; FIXME: send to result-chan if v is eval result
          (recur (do-in client-state msg)))
        )))

  (async/close! result-chan))

(defn ws-start
  [{:keys [build ring-request] :as req}]

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

        ;; "/ws/eval/<build-id>/<client-id>/<client-type>"
        [_ _ _ proc-id client-id client-type :as parts]
        (str/split uri #"/")

        proc-id
        (UUID/fromString proc-id)

        ;; FIXME: none-devtools repl clients
        client-type
        (keyword client-type)

        build-proc
        (build/get-proc-by-id build proc-id)]

    (if-not build-proc
      common/not-found

      (let [eval-out
            (-> (async/sliding-buffer 10)
                (async/chan))

            result-chan
            (build/repl-eval-connect build-proc client-id eval-out)

            watch-chan
            (-> (async/sliding-buffer 10)
                (async/chan))

            client-state
            {:app req
             :client-id client-id
             :proc-id proc-id
             :in client-in
             :out client-out
             :eval-out eval-out
             :result-chan result-chan
             :watch-chan watch-chan}]

        (build/watch build-proc watch-chan)

        (-> (http/websocket-connection ring-request)
            (md/chain
              (fn [socket]
                (ms/connect socket client-in)
                (ms/connect client-out socket)
                socket))
            (md/chain
              (fn [socket]
                (thread (ws-loop! (assoc client-state :socket socket)))
                ))
            (md/catch common/unacceptable))))))

