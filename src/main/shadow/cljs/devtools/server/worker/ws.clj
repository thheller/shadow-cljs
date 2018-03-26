(ns shadow.cljs.devtools.server.worker.ws
  "the websocket which is injected into the app, responsible for live-reload, repl-eval, etc"
  (:require [clojure.core.async :as async :refer (go <! >! thread alt!! >!!)]
            [clojure.edn :as edn]
            [shadow.build.output :as output]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.build :as comp]
            [shadow.http.router :as http]
            [shadow.build.data :as data]
            [shadow.build.resource :as rc]
            [clojure.tools.logging :as log])
  (:import (java.util UUID)))

(defn ws-loop!
  [{:keys [worker-proc watch-chan eval-out in out result-chan] :as client-state}]
  (let [{:keys [system-bus]} worker-proc]

    (worker/watch worker-proc watch-chan true)

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
  [{:keys [ring-request] :as ctx}
   {:keys [output] :as worker-proc} client-id client-type]

  (let [{:keys [ws-in ws-out]} ring-request

        eval-out
        (-> (async/sliding-buffer 10)
            (async/chan))

        result-chan
        (worker/repl-eval-connect worker-proc client-id eval-out)

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
         :in ws-in
         :out ws-out
         :eval-out eval-out
         :result-chan result-chan
         :watch-chan watch-chan}]

    (thread (ws-loop! client-state))
    ))

(defn ws-listener-connect
  [{:keys [ring-request] :as ctx}
   {:keys [output] :as worker-proc} client-id]

  (let [{:keys [ws-in ws-out]} ring-request

        ;; FIXME: let the client decide?
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
              (remove #(contains? watch-ignore (:type %)))))]

    (worker/watch worker-proc watch-chan true)

    (let [last-known-state
          (-> worker-proc :state-ref deref :build-state ::comp/build-info)]
      (>!! ws-out {:type :build-init
                   :info last-known-state}))

    ;; close watch when websocket closes
    (go (loop []
          (when-some [msg (<! ws-in)]
            (log/warn "ignored listener msg" msg)
            (recur)))
        (async/close! watch-chan))

    (go (loop []
          (when-some [msg (<! watch-chan)]
            (>! ws-out msg)
            (recur))
          ))))

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
         :body (pr-str result)})

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
                     (let [{:keys [resource-name type output-name ns output] :as src}
                           (data/get-source-by-id build-state src-id)

                           {:keys [js] :as output}
                           (data/get-output! build-state src)]

                       {:resource-name resource-name
                        :resource-id src-id
                        :output-name output-name

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
                                "" #_ (output/js-module-src-append build-state src)

                                sm-text
                                (output/generate-source-map-inline build-state src output prepend)]

                            (str prepend js append sm-text)))
                        })))
              (into [])
              (pr-str))})

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


(defn process-ws
  [{::http/keys [path-tokens] :keys [supervisor] :as ctx}]

  ;; "/worker/browser/430da920-ffe8-4021-be47-c9ca77c6defd/305de5d9-0272-408f-841e-479937512782/browser"
  ;; _ _ to drop / and worker
  (let [[action build-id proc-id client-id client-type :as x]
        path-tokens

        build-id
        (keyword build-id)

        proc-id
        (UUID/fromString proc-id)

        ws-out
        (get-in ctx [:ring-request :ws-out])

        worker-proc
        (super/get-worker supervisor build-id)]

    (cond
      (nil? worker-proc)
      (go (>! ws-out {:type :client/no-worker}))
      ;; can't send {:status 404 :body "no worker"}
      ;; as there appears to be no way to access either the status code or body
      ;; on the client via the WebSocket API to know why a websocket connection failed
      ;; onerror returns nothing useful only that it failed
      ;; so instead we pretend to handshake properly, send one message and disconnect

      (not= proc-id (:proc-id worker-proc))
      (go (>! ws-out {:type :client/stale}))

      :else
      (case action
        "worker"
        (ws-connect ctx worker-proc client-id client-type)

        "listener"
        (ws-listener-connect ctx worker-proc client-id)

        :else
        nil
        ))))

