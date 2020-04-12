(ns shadow.cljs.devtools.server.worker.ws
  "the websocket which is injected into the app, responsible for live-reload, repl-eval, etc"
  (:require
    [clojure.core.async :as async :refer (go <! >! thread alt!! <!! >!!)]
    [clojure.edn :as edn]
    [shadow.user]
    [shadow.build.output :as output]
    [shadow.cljs.model :as m]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.cljs.devtools.server.repl-system :as repl-sys]
    [shadow.build :as comp]
    [shadow.http.router :as http]
    [shadow.build.data :as data]
    [shadow.build.resource :as rc]
    [shadow.jvm-log :as log]
    [shadow.core-ext :as core-ext])
  (:import (java.util UUID)))

(defn ws-loop!
  "this loop just ensures that all messages get routed to the correct places"
  [{:keys [repl-system worker-proc runtime-id runtime-info ws-in ws-out] :as ws-state}]
  ;; no need to forward :build-log messages to the client
  (let [{:keys [proc-stop proc-control]} worker-proc

        watch-ignore
        #{:build-log
          :repl/out
          :repl/err
          :repl/result
          :repl/error ;; server-side error
          :repl/action
          :repl/runtime-connect
          :repl/runtime-disconnect
          :repl/client-start
          :repl/client-stop}

        watch-chan
        (-> (async/sliding-buffer 10)
            (async/chan
              (remove #(contains? watch-ignore (:type %)))))

        ;; tool-out is for messages coming from the worker forwarded to the tools
        ;; will be closed when either the worker stops or the runtime disconnects
        tool-out
        (async/chan 10)

        ;; tool-in is forwarding messages from tools to the worker
        tool-in
        (repl-sys/runtime-connect repl-system runtime-id runtime-info tool-out)]

    (>!! proc-control {:type :runtime-connect
                       :runtime-id runtime-id
                       :runtime-out ws-out
                       :runtime-info runtime-info
                       :tool-out tool-out})

    (worker/watch worker-proc watch-chan true)

    (loop []

      (alt!!
        ;; worker exit
        proc-stop
        ([msg]
          ::stopped)

        ;; repl-system -> worker
        tool-in
        ([msg]
          (when-not (nil? msg)
            (>!! proc-control {:type (::m/op msg)
                               :runtime-id runtime-id
                               :runtime-out ws-out
                               :tool-id (::m/tool-id msg)
                               :tool-out tool-out
                               :msg msg})
            (recur)))

        ;; forward some build watch messages to the client
        watch-chan
        ([msg]
          (when-not (nil? msg)
            (>!! ws-out msg)
            (recur)))

        ;; ws -> worker
        ws-in
        ([msg]
          (when-not (nil? msg)
            (>!! proc-control {:type :runtime-msg
                               :runtime-id runtime-id
                               :runtime-out ws-out
                               :msg msg})
            (recur)))))

    ;; FIXME: don't need to send this if proc-stop triggered the loop exit
    (>!! proc-control {:type :runtime-disconnect
                       :runtime-id runtime-id})

    (async/close! tool-out)
    (async/close! watch-chan)
    ))

(defn ws-connect
  [{:keys [ring-request repl-system system-bus] :as ctx} worker-proc runtime-id runtime-type]

  (let [ws-in (async/chan 10 (map edn/read-string))
        ws-out (async/chan 10 (map pr-str))

        runtime-info
        {:runtime-type runtime-type
         :lang :cljs
         :build-id (:build-id worker-proc)
         :user-agent (get-in ctx [:ring-request :headers "user-agent"])
         :remote-addr (:remote-addr ring-request)}

        ws-state
        {:repl-system repl-system
         :system-bus system-bus
         :worker-proc worker-proc
         :runtime-id runtime-id
         :runtime-type runtime-type
         :runtime-info runtime-info
         :ws-in ws-in
         :ws-out ws-out}]

    {:ws-in ws-in
     :ws-out ws-out
     :ws-loop (thread (ws-loop! ws-state))}
    ))

(defn compile-req
  [{:keys [ring-request transit-str transit-read] :as ctx} worker-proc]
  (let [{:keys [request-method body]}
        ring-request

        headers
        {"Access-Control-Allow-Origin" "*"
         "Access-Control-Allow-Headers"
         (or (get-in ring-request [:headers "access-control-request-headers"])
             "content-type")
         "content-type" "application/transit+json; charset=utf-8"}]

    ;; CORS sends OPTIONS first
    (case request-method

      :options
      {:status 200
       :headers headers
       :body ""}

      :post
      (let [msg
            (-> (slurp body)
                (transit-read))

            result
            (worker/repl-compile worker-proc msg)]
        {:status 200
         :headers headers
         :body (transit-str result)})

      ;; bad requests
      {:status 400
       :body "Only POST or OPTIONS requests allowed."}
      )))

(defn files-req
  "a POST request from the REPL client asking for the compile JS for sources by name
   sends a {:sources [...]} structure with a vector of source names
   the response will include [{:js code :name ...} ...] with :js ready to eval"
  [{:keys [ring-request] :as ctx}
   {:keys [state-ref] :as worker-proc}]

  (let [{:keys [request-method body]}
        ring-request

        headers
        {"Access-Control-Allow-Origin" "*"
         "Access-Control-Allow-Headers"
         (or (get-in ring-request [:headers "access-control-request-headers"])
             "content-type")
         "content-type" "application/edn; charset=utf-8"}]



    ;; CORS sends OPTIONS first
    (case request-method

      :options
      {:status 200
       :headers headers
       :body ""}

      :post
      (let [text
            (slurp body)

            {:keys [sources] :as req}
            (edn/read-string text)

            build-state
            (:build-state @state-ref)

            module-format
            (get-in build-state [:build-options :module-format])]

        {:status 200
         :headers headers
         :body
         (->> sources
              (map (fn [src-id]
                     (assert (rc/valid-resource-id? src-id))
                     (let [{:keys [resource-name type output-name ns provides] :as src}
                           (data/get-source-by-id build-state src-id)

                           {:keys [js] :as output}
                           (data/get-output! build-state src)]

                       {:resource-name resource-name
                        :resource-id src-id
                        :output-name output-name
                        :type type
                        :ns ns
                        :provides provides

                        ;; FIXME: make this pretty ...
                        :js
                        (case module-format
                          :goog
                          (let [sm-text (output/generate-source-map-inline build-state src output "")]
                            (str js sm-text))
                          :js
                          (let [prepend
                                (output/js-module-src-prepend build-state src false)

                                append
                                "" #_(output/js-module-src-append build-state src)

                                sm-text
                                (output/generate-source-map-inline build-state src output prepend)]

                            (str prepend js append sm-text)))
                        })))
              (into [])
              (core-ext/safe-pr-str))})

      ;; bad requests
      {:status 400
       :body "Only POST or OPTIONS requests allowed."}
      )))

(defn process-req
  [{::http/keys [path-tokens] :keys [supervisor] :as ctx}]

  ;; "/worker/browser/430da920-ffe8-4021-be47-c9ca77c6defd/305de5d9-0272-408f-841e-479937512782/browser"
  ;; _ _ to drop / and worker

  (let [[action build-id proc-id client-id client-type :as x]
        path-tokens

        build-id
        (keyword build-id)

        proc-id
        (UUID/fromString proc-id)

        worker-proc
        (super/get-worker supervisor build-id)]

    (cond
      (nil? worker-proc)
      {:status 404
       :body "No worker for build."}

      (not= proc-id (:proc-id worker-proc))
      {:status 403
       :body "stale client, please reload"}

      :else
      (case action
        "files"
        (files-req ctx worker-proc)

        "compile"
        (compile-req ctx worker-proc)

        :else
        {:status 404
         :headers {"content-type" "text/plain"}
         :body "Not found."}))))

(defn send-ws-error [error-code]
  (let [ws-out (async/chan 1 (map pr-str))]
    {:ws-in (async/chan)
     :ws-out ws-out
     :ws-loop
     (go (>! ws-out {:type error-code}))}))

(defn process-ws
  [{::http/keys [path-tokens] :keys [supervisor] :as ctx}]

  ;; "/worker/browser/430da920-ffe8-4021-be47-c9ca77c6defd/305de5d9-0272-408f-841e-479937512782/browser"
  (let [[action build-id proc-id runtime-id runtime-type :as x]
        path-tokens

        build-id
        (keyword build-id)

        proc-id
        (UUID/fromString proc-id)

        runtime-type
        (keyword runtime-type)

        worker-proc
        (super/get-worker supervisor build-id)]

    (cond
      (nil? worker-proc)
      (send-ws-error :client/no-worker)
      ;; can't send {:status 404 :body "no worker"}
      ;; as there appears to be no way to access either the status code or body
      ;; on the client via the WebSocket API to know why a websocket connection failed
      ;; onerror returns nothing useful only that it failed
      ;; so instead we pretend to handshake properly, send one message and disconnect

      (not= proc-id (:proc-id worker-proc))
      (send-ws-error :client/stale)

      :else
      (case action
        "worker"
        (ws-connect ctx worker-proc runtime-id runtime-type)

        :else
        nil
        ))))

