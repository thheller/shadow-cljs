(ns shadow.devtools.server.services.build
  (:refer-clojure :exclude (compile))
  (:require [clojure.core.async :as async :refer (go thread alt!! alt! <!! <! >! >!!)]
            [aleph.http :as aleph]
            [shadow.devtools.server.util :as util]
            [shadow.devtools.server.services.fs-watch :as fs-watch]
            [shadow.devtools.server.services.build.impl :as impl]
            [shadow.devtools.server.services.build.ws :as ws]
            [aleph.netty :as netty])
  (:import (java.util UUID)))

(defn configure
  "re-configure the build"
  [proc {:keys [id] :as config}]
  {:pre [(map? config)
         (keyword? id)]}
  (impl/configure proc config))

(defn compile
  "triggers an async compilation, use watch to receive notification about build state"
  [proc]
  (impl/compile proc))

(defn compile!
  "triggers a compilation and waits for completion blocking the current thread"
  [proc]
  (impl/compile! proc))

(defn watch
  "watch all output produced by the build"
  ([proc chan]
    (watch proc chan true))
  ([proc chan close?]
   (impl/watch proc chan close?)))

(defn start-autobuild
  "automatically compile on file changes"
  [proc]
  (impl/start-autobuild proc))

(defn stop-autobuild [proc]
  (impl/stop-autobuild proc))

(defn repl-state
  "queries current state of repl (current ns, ...), written to chan"
  [proc chan]
  (impl/repl-state proc chan))

(defn repl-eval-connect
  "called by processes that are able to eval repl commands and report their result

   client-out should be a channel that receives things generated by shadow.cljs.repl
   (:repl/invoke, :repl/require, etc)

   returns a channel the results of eval should be put in
   when no more results are coming this channel should be closed"
  [proc client-id client-out]
  (impl/repl-eval-connect proc client-id client-out))

(defn repl-client-connect
  "connects to a running build as a repl client who can send things to eval and receive their result

   client-in should receive strings which represent cljs code
   will remove the client when client-in closes
   returns a channel that will receive results from client-in
   the returned channel is closed if the build is stopped"
  [proc client-id client-in]
  (impl/repl-client-connect proc client-id client-in))

;; SERVICE API

(defn start [fs-watch]
  (let [proc-id
        (UUID/randomUUID) ;; FIXME: not really unique but unique enough

        ;; closed when the proc-stops
        ;; nothing will ever be written here
        ;; its for linking other processes to the server process
        ;; so they shut down when the build stops
        proc-stop
        (async/chan)

        ;; controls the build, registers new clients, etc
        proc-control
        (async/chan)

        ;; we put output here
        output
        (async/chan)

        ;; clients tap here to receive output
        output-mult
        (async/mult output)

        ;; for commands received to be compiled and eval'd by the repl
        repl-in
        (async/chan)

        ;; results received by eval-clients
        repl-result
        (async/chan)

        fs-updates
        (async/chan)

        config-updates
        (async/chan)

        http-info-ref
        (volatile! nil) ;; {:port 123 :localhost foo}

        channels
        {:proc-stop proc-stop
         :proc-control proc-control
         :output output
         :repl-in repl-in
         :repl-result repl-result
         :config-updates config-updates
         :fs-updates fs-updates}

        thread-state
        {::proc-state true
         :http-info-ref http-info-ref
         :proc-id proc-id
         :eval-clients {}
         :repl-clients {}
         :pending-results {}
         :channels channels
         :compiler-state nil}

        state-ref
        (volatile! thread-state)

        thread-ref
        (util/server-thread
          state-ref
          thread-state
          {proc-stop nil
           proc-control impl/do-proc-control
           repl-in impl/do-repl-in
           repl-result impl/do-repl-result
           config-updates impl/do-config-updates
           fs-updates impl/do-fs-updates}
          {:do-shutdown
           (fn [state]
             (>!! output {:type :build-shutdown})
             state)})

        proc-info
        {::impl/proc true
         :proc-stop proc-stop
         :proc-id proc-id
         :proc-control proc-control
         :fs-updates fs-updates
         :output output
         :output-mult output-mult
         :repl-in repl-in
         :repl-result repl-result
         :thread-ref thread-ref
         :state-ref state-ref}]

    (fs-watch/subscribe fs-watch fs-updates)

    ;; ensure all channels are cleanup up properly
    (go (<! thread-ref)
        (async/close! output)
        (async/close! proc-stop)
        (async/close! fs-updates)
        (async/close! repl-in)
        (async/close! repl-result))

    (let [http-config
          {:port 0
           :host "localhost"}

          http
          (aleph/start-server
            (fn [ring]
              (ws/process (assoc proc-info :http @http-info-ref) ring))
            http-config)

          http-config
          (assoc http-config
            :port (netty/port http))]

      (vreset! http-info-ref http-config)
      (assoc proc-info
        :http-info http-config
        :http http))))

(defn stop [{:keys [http] :as proc}]
  {:pre [(impl/proc? proc)]}
  (.close http)
  (async/close! (:proc-stop proc))
  (<!! (:thread-ref proc)))
