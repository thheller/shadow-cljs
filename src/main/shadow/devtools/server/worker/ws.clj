(ns shadow.devtools.server.worker.ws
  "the websocket which is injected into the app, responsible for live-reload, repl-eval, etc"
  (:require [shadow.devtools.server.worker.impl :as impl]
            [shadow.devtools.server.web.common :as common]
            [clojure.core.async :as async :refer (thread alt!! >!!)]
            [clojure.string :as str]
            [aleph.http :as http]
            [manifold.deferred :as md]
            [manifold.stream :as ms]
            [clojure.edn :as edn])
  (:import (java.util UUID)))

(defn ws-loop!
  [{:keys [worker-proc watch-chan result-chan] :as client-state}]

  (impl/watch worker-proc watch-chan true)

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
          (>!! out msg)
          (recur client-state)))

      ;; forward some build watch messages to the client
      watch-chan
      ([msg]
        (when-not (nil? msg)
          (>!! out msg)
          (recur client-state)
          ))

      in
      ([msg]
        (when-not (nil? msg)
          (>!! result-chan msg)
          (recur client-state))
        )))

  (async/close! result-chan))

(defn process
  [{:keys [output] :as worker-proc} {:keys [uri] :as req}]

  ;; "/ws/client/<proc-id>/<client-id>/<client-type>"
  ;; if proc-id does not match there is old js connecting to a new process
  ;; should probably not allow that
  ;; unlikely due to random port but still shouldn't allow it

  (let [[_ _ _ proc-id client-id client-type :as parts]
        (str/split uri #"/")

        proc-id
        (UUID/fromString proc-id)]

    (if (not= proc-id (:proc-id worker-proc))
      (do (>!! output {:type :rejected-client
                       :proc-id proc-id
                       :client-id client-id})
          (common/unacceptable req))

      (let [client-in
            (async/chan
              1
              (map edn/read-string))

            client-out
            (async/chan
              (async/sliding-buffer 10)
              (map pr-str))

            client-type
            (keyword client-type)

            eval-out
            (-> (async/sliding-buffer 10)
                (async/chan))

            result-chan
            (impl/repl-eval-connect worker-proc client-id eval-out)

            ;; watch messages are forwarded to the client
            ;; could send everything there but most of it is uninterested
            ;; dont need to see build-log as it should be displayed elsewhere already
            ;; not interested in anything else for now
            ;; :build-complete is for live-reloading
            watch-forward
            #{:build-complete
              :build-failure}

            watch-chan
            (-> (async/sliding-buffer 10)
                (async/chan
                  (filter #(contains? watch-forward (:type %)))))

            client-state
            {:worker-proc worker-proc
             :client-id client-id
             :client-type client-type
             :in client-in
             :out client-out
             :eval-out eval-out
             :result-chan result-chan
             :watch-chan watch-chan}]

        (-> (http/websocket-connection req
              {:headers
               (let [proto (get-in req [:headers "sec-websocket-protocol"])]
                 (if (seq proto)
                   {"sec-websocket-protocol" proto}
                   {}))})
            (md/chain
              (fn [socket]
                (ms/connect socket client-in)
                (ms/connect client-out socket)
                socket))

            ;; FIXME: why the second chain?
            (md/chain
              (fn [socket]
                (thread (ws-loop! (assoc client-state :socket socket)))
                ))
            (md/catch common/unacceptable))))))

