(ns shadow.devtools.server.services.build
  (:require [shadow.devtools.server.services.fs-watch :as fs-watch]
            [clojure.core.async :as async :refer (go thread alt!! alt! <!! <! >! >!!)]
            [shadow.devtools.server.util :as util]
            [clojure.tools.logging :as log]
            [clojure.pprint :refer (pprint)]
            [shadow.devtools.server.services.build.impl :as impl]
            )
  (:import (java.util UUID)))

(defn- service? [x]
  (and (map? x)
       (::service x)))

(defn- proc? [x]
  (and (map? x)
       (::proc x)))


;; PROC API

(defn configure
  [{:keys [proc-control] :as proc} mode config]
  {:pre [(proc? proc)]}
  (>!! proc-control {:type :configure :mode mode :config config})
  proc)

(defn watch
  [{:keys [output-mult] :as proc} log-chan]
  {:pre [(proc? proc)]}
  (async/tap output-mult log-chan)
  proc)

(defn start-autobuild
  [{:keys [proc-control] :as proc}]
  {:pre [(proc? proc)]}
  (>!! proc-control {:type :start-autobuild})
  proc)

(defn repl-eval-connect
  "called by processes that are able to eval repl commands and report their result

   client-out should be a channel that receives things that should be eval'd

   returns a channel the results of eval should be put in
   when no more results are coming this channel should be closed"
  [{:keys [proc-stop proc-control repl-result] :as proc} client-id client-out]
  {:pre [(proc? proc)]}
  ;; result-chan
  ;; creating a new chan here instead of just handing out repl-result
  ;; closing it is currently the only way to a eval-client can signal a disconnect
  ;; we will however just pipe messages through as we have nothing useful to do with them

  ;; eval-out
  ;; FIXME: just piping through but could just talk to client-out directly?
  (let [result-chan
        (async/chan)

        eval-out
        (async/chan)]

    (go (>! proc-control {:type :eval-start
                           :id client-id
                           :eval-out eval-out})

        (loop []
          (alt!
            proc-stop
            ([_] nil)

            result-chan
            ([msg]
              (when-not (nil? msg)
                (>! repl-result msg)
                (recur)))

            eval-out
            ([msg]
              (when-not (nil? msg)
                (>! client-out msg)
                (recur)))
            ))

        (>! proc-control {:type :eval-stop
                           :id client-id})

        (async/close! eval-out)
        (async/close! result-chan))

    result-chan))

(defn repl-client-connect
  "connects to a running build as a repl client who can send things to eval and receive their result

   client-in should receive strings which represent cljs code
   will remove the client when client-in closes
   returns a channel that will receive results from client-in
   the returned channel is closed if the build is stopped"
  [{:keys [proc-stop proc-control repl-in] :as proc} client-id client-in]
  {:pre [(proc? proc)]}

  (let [client-result
        (async/chan)]

    (go (>! proc-control {:type :client-start
                           :id client-id
                           :in client-in})

        (loop []
          (alt!
            proc-stop
            ([_] nil)

            client-in
            ([v]
              (when-not (nil? v)
                (assert (string? v))
                (>! repl-in {:code v
                             :reply-to client-result})
                (recur)
                ))))

        (>! proc-control {:type :client-stop
                           :id client-id})

        (async/close! client-result))

    client-result
    ))

;; SERVICE API

(defn active-builds [svc]
  {:pre [(service? svc)]}
  (keys @(:process-ref svc)))

(defn get-proc-by-id
  [{:keys [process-ref] :as svc} proc-id]
  {:pre [(service? svc)]}
  (get @process-ref proc-id))

(defn find-proc-by-build-id
  [{:keys [process-ref] :as svc} build-id]
  {:pre [(service? svc)]}
  (let [procs
        (->> @process-ref
             (vals)
             (filter (fn [{:keys [state-ref] :as proc}]
                       (let [state @state-ref]
                         (= build-id (get-in state [:build-config :id])))))
             (into []))

        c
        (count procs)]

    (condp = c
      1 (first procs)
      0 nil
      (throw (ex-info "more than one process for id" {:build-id build-id :procs (into #{} (map :proc-id) procs)}))
      )))

(defn proc-start
  "starts a new process dedicated to the build
   commands can be sent to proc-control"
  [{:keys [process-ref fs-watch] :as svc}]
  {:pre [(service? svc)]}

  (let [;; closed when the proc-stops
        ;; nothing will ever be written here
        ;; its for linking other processes to the server process
        ;; so they shut down when the build stops
        proc-id
        (UUID/randomUUID)

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

        channels
        {:proc-stop proc-stop
         :proc-control proc-control
         :output output
         :repl-in repl-in
         :repl-result repl-result
         :config-updates config-updates
         :fs-updates fs-updates}

        proc-state
        {::proc-state true
         :proc-id proc-id
         :svc svc
         :eval-clients {}
         :repl-clients {}
         :pending-results {}
         :channels channels
         :mode :dev
         :compiler-state nil}

        state-ref
        (volatile! proc-state)

        thread-ref
        (util/server-thread
          state-ref
          proc-state
          {proc-stop nil
           proc-control impl/do-proc-control
           repl-in impl/do-repl-in
           repl-result impl/do-repl-result
           config-updates impl/do-config-updates
           fs-updates impl/do-fs-updates}
          {})

        proc-info
        {::proc true
         :proc-stop proc-stop
         :proc-control proc-control
         :fs-updates fs-updates
         :output output
         :output-mult output-mult
         :repl-in repl-in
         :repl-result repl-result
         :thread-ref thread-ref
         :state-ref state-ref}]

    (vswap! process-ref assoc proc-id proc-info)

    (fs-watch/subscribe fs-watch fs-updates)

    (go (<! thread-ref)
        (vswap! process-ref dissoc proc-id)
        (async/close! output)
        (async/close! proc-stop)
        (async/close! fs-updates)
        (async/close! repl-in)
        (async/close! repl-result))

    proc-info
    ))

(defn proc-stop [{:keys [process-ref] :as svc} proc-id]
  {:pre [(service? svc)]}
  (when-let [proc (get @process-ref proc-id)]
    (async/close! (:proc-stop proc))
    (<!! (:thread-ref proc))
    ))

(defn start [fs-watch]
  {::service true
   :fs-watch fs-watch
   :process-ref (volatile! {})})

(defn stop [{:keys [process-ref] :as svc}]
  {:pre [(service? svc)]}
  (doseq [[proc-id proc] @process-ref]
    (proc-stop svc proc-id)))
