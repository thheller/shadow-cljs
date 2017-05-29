(ns shadow.cljs.devtools.server.worker.ws
  "the websocket which is injected into the app, responsible for live-reload, repl-eval, etc"
  (:require [shadow.cljs.devtools.server.worker.impl :as impl]
            [shadow.cljs.devtools.server.web.common :as common]
            [clojure.core.async :as async :refer (thread alt!! >!!)]
            [clojure.string :as str]
            [aleph.http :as http]
            [manifold.deferred :as md]
            [manifold.stream :as ms]
            [clojure.edn :as edn]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [clojure.java.io :as io]
            [shadow.cljs.output :as output])
  (:import (java.util UUID)))

(defn ws-loop!
  [{:keys [worker-proc watch-chan eval-out in out result-chan] :as client-state}]
  (let [{:keys [system-bus]} worker-proc]

    (impl/watch worker-proc watch-chan true)

    ;; FIXME: the client should probably trigger this
    ;; a node-repl isn't interested in this at all
    (sys-bus/sub system-bus ::sys-msg/css-reload out false)

    (loop [client-state client-state]

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

    (async/close! result-chan)))

(defn ws-connect
  [{:keys [output] :as worker-proc} {:keys [uri] :as req}]
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

            ;; FIXME: n=10 is rather arbitrary
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

            ;; no need to forward :build-log messages to the client
            watch-ignore
            #{:build-log
              :repl/result
              :repl/error ;; server-side error
              :repl/action
              :repl/eval-start
              :repl/eval-stop
              :repl/client-start
              :repl/client-stop}

            watch-chan
            (-> (async/sliding-buffer 10)
                (async/chan
                  (remove #(contains? watch-ignore (:type %)))))

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

(defn file-req [{:keys [state-ref] :as worker-proc} {:keys [uri] :as req}]
  (let [{:keys [output-dir module-format] :as compiler-state}
        (:compiler-state @state-ref)

        filename
        (-> (subs uri 6)
            (cond->
              (= :js module-format)
              (output/flat-js-name)))

        file
        (if (= :js module-format)
          (io/file output-dir filename)
          (io/file output-dir "cljs-runtime" filename))]

    {:status 200
     :headers {"cache-control" "no-store, must-revalidate, max-age=0"
               "content-type" "text/javascript"}
     :body (slurp file)}))

(defn process
  [{:keys [output] :as worker-proc} {:keys [uri] :as req}]

  ;; "/ws/client/<proc-id>/<client-id>/<client-type>"
  ;; if proc-id does not match there is old js connecting to a new process
  ;; should probably not allow that
  ;; unlikely due to random port but still shouldn't allow it

  (cond
    (str/starts-with? uri "/file/")
    (file-req worker-proc req)

    (str/starts-with? uri "/ws/client/")
    (ws-connect worker-proc req)))

