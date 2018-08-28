(ns shadow.cljs.devtools.server.worker.ws
  "the websocket which is injected into the app, responsible for live-reload, repl-eval, etc"
  (:require
    [clojure.core.async :as async :refer (go <! >! thread alt!! <!! >!!)]
    [clojure.edn :as edn]
    [shadow.user]
    [shadow.build.output :as output]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs.api.system :as sys-msg]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.build :as comp]
    [shadow.http.router :as http]
    [shadow.build.data :as data]
    [shadow.build.resource :as rc]
    [shadow.jvm-log :as log]
    [shadow.core-ext :as core-ext])
  (:import (java.util UUID)))

(defn ws-loop!
  [{:keys [worker-proc watch-chan in out runtime-out runtime-in] :as client-state}]
  (let [{:keys [system-bus]} worker-proc]

    (worker/watch worker-proc watch-chan true)

    ;; FIXME: the client should probably trigger this
    ;; a node-repl isn't interested in this at all
    (sys-bus/sub system-bus ::sys-msg/css-reload out false)

    (loop [client-state client-state]

      (alt!!
        runtime-out
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
            (>!! runtime-in msg)
            (recur client-state))
          )))

    (async/close! runtime-in)))

(defn ws-connect
  [{:keys [ring-request] :as ctx} worker-proc runtime-id runtime-type]

  (let [ws-in (async/chan 10 (map edn/read-string))
        ws-out (async/chan 10 (map pr-str))

        ;; messages put on this must be forwarded to the runtime
        runtime-out
        (-> (async/sliding-buffer 10)
            (async/chan))

        ;; messages coming from the runtime must be put on runtime-in
        runtime-in
        (worker/repl-runtime-connect worker-proc runtime-id runtime-out
          {:runtime-type runtime-type
           :user-agent (get-in ctx [:ring-request :headers "user-agent"])
           :remote-addr (:remote-addr ring-request)})

        ;; no need to forward :build-log messages to the client
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

        client-state
        {:worker-proc worker-proc
         :runtime-id runtime-id
         :runtime-type runtime-type
         :in ws-in
         :out ws-out
         :runtime-out runtime-out
         :runtime-in runtime-in
         :watch-chan watch-chan}]

    {:ws-in ws-in
     :ws-out ws-out
     :ws-loop (thread (ws-loop! client-state))}
    ))

(defn compile-req
  [{:keys [ring-request] :as ctx} worker-proc]
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
      (let [{:keys [input]}
            (-> (slurp body)
                (edn/read-string))

            result (worker/repl-compile worker-proc input)]
        {:status 200
         :headers headers
         :body (core-ext/safe-pr-str result)})

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

