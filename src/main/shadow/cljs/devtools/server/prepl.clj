(ns shadow.cljs.devtools.server.prepl
  (:require
    [clojure.core.async :as async :refer (go <! <!! >! >!! alts! alt!!)]
    [shadow.cljs.repl :as repl]
    [shadow.cljs.model :as m]
    [shadow.cljs.devtools.server.repl-system :as repl-system]
    [shadow.build.warnings :as warnings]
    [shadow.cljs.devtools.api :as shadow]
    [shadow.core-ext :as core-ext]
    [shadow.jvm-log :as log])
  (:import [java.net ServerSocket InetAddress SocketException]
           [java.util UUID]
           [java.io OutputStreamWriter BufferedWriter InputStreamReader]))

;; from clojure.core.server/prepl
;;   Calls out-fn with data, one of:
;;  {:tag :ret
;;   :val val ;;eval result
;;   :ns ns-name-string
;;   :ms long ;;eval time in milliseconds
;;   :form string ;;iff successfully read
;;   :clojure.error/phase (:execution et al per clojure.main/ex-triage) ;;iff error occurred
;;  }
;;  {:tag :out
;;   :val string} ;chars from during-eval *out*
;;  {:tag :err
;;   :val string} ;chars from during-eval *err*

(defn client-loop
  [{:keys [state-ref repl-system] :as svc}
   {:keys [build-id server-close server-socket] :as server-info}
   client-id
   socket]
  (let [socket-in
        (-> (.getInputStream socket)
            (InputStreamReader.))

        socket-out
        (-> (.getOutputStream socket)
            (OutputStreamWriter.)
            (BufferedWriter.))

        out-lock
        (Object.)

        send!
        (fn send!
          ([msg]
           (send! core-ext/safe-pr-str msg))
          ([transform-fn msg]
           (locking out-lock
             (doto socket-out
               (.write (transform-fn msg))
               (.write "\n")
               (.flush)))))

        socket-msg
        (async/chan) ;; no buffer, avoids reading too far ahead?

        tool-in
        (async/chan 10)

        tool-out
        (repl-system/tool-connect repl-system (str "prepl:" client-id) tool-in)

        runtimes
        (repl-system/find-runtimes-for-build repl-system build-id)]

    ;; read loop, blocking IO
    (async/thread
      (try
        (loop []
          (let [{:keys [eof?] :as next} (repl/dummy-read-one socket-in)]
            (if eof?
              (async/close! socket-msg)
              (do (>!! socket-msg next)
                  (recur)))))
        (catch Exception e
          (log/warn-ex e ::socket-exception)
          (async/close! socket-msg))))

    ;; FIXME: the client should pick which runtime it wants
    ;; (send! {:tag :runtimes :runtimes runtimes})

    (if (empty? runtimes)
      (send! {:tag :err :val "No available JS runtimes!"})
      ;; work loop
      (let [session-id (str (UUID/randomUUID))
            session-ns 'cljs.user
            runtime-id (-> runtimes first :runtime-id)]

        (>!! tool-in {::m/op ::m/session-start
                      ::m/runtime-id runtime-id
                      ::m/session-id session-id
                      ::m/session-ns session-ns})
        (loop [loop-state {:session-id session-id
                           :session-ns session-ns
                           :runtime-id runtime-id}]
          (alt!!
            server-close
            ([_]
             (send! {:tag :err :val "The server is shutting down."})
             :close)

            ;; input from tool
            socket-msg
            ([msg]
             (when-not (nil? msg)
               (let [{:keys [error? ex source]} msg]
                 (cond
                   error?
                   (do (send! {:tag :err :val (str "Failed to read: " ex)})
                       (recur loop-state))

                   (= ":repl/quit" source)
                   :quit

                   (= ":cljs/quit" source)
                   :quit

                   :else
                   (do (>!! tool-in {::m/op ::m/session-eval
                                     ::m/runtime-id runtime-id
                                     ::m/session-id session-id
                                     ::m/input-text source})
                       (recur loop-state))))))

            ;; messages from repl-system
            tool-out
            ([{::m/keys [op] :as msg}]
             (when (some? msg)
               (case op
                 ::m/session-started
                 (recur loop-state)

                 ::m/session-update
                 (let [{::m/keys [session-ns]} msg]
                   (-> loop-state
                       (assoc :session-ns session-ns)
                       (recur)))

                 ::m/session-result
                 (let [{:keys [session-ns]} loop-state
                       {::m/keys [printed-result form eval-ms]} msg]
                   ;; worst hack in history to prevent having to read-string the result
                   (send! {:tag :ret
                           :ns (pr-str session-ns)
                           :form form
                           :ms (or eval-ms 0)
                           :val printed-result})
                   (recur loop-state))

                 ::m/session-out
                 (do (when (= session-id (::m/session-id msg))
                       (send! {:tag :out :val (::m/text msg)}))
                     (recur loop-state))

                 ::m/session-err
                 (do (when (= session-id (::m/session-id msg))
                       (send! {:tag :err :val (::m/text msg)}))
                     (recur loop-state))

                 ::m/runtime-disconnect
                 (if-not (= runtime-id (::m/runtime-id msg))
                   (recur loop-state)
                   ;; inform client and let loop end, ending in disconnect
                   (send! {:tag :err :val "The JS Runtime disconnected."}))

                 ;; mostly likely coming after disconnect on the browser reload
                 ;; FIXME: should disconnect not actually disconnect but rather just wait?
                 ;; this could also be a second browser connecting
                 ::m/runtime-connect
                 (recur loop-state)

                 ;; default
                 (do (log/debug ::unhandled-tool-out {:msg msg})
                     (recur loop-state)))))))

        ;; prepl has no way to ever resume a session
        (>!! tool-in {::m/op ::m/session-close
                      ::m/runtime-id runtime-id
                      ::m/session-id session-id})))

    (swap! state-ref update-in [:server server-socket :clients] dissoc client-id)
    (async/close! tool-in)
    (.close socket)))

(defn server-loop
  [{:keys [state-ref] :as svc} {:keys [server-socket] :as server-info}]
  (try
    (loop [id 0]
      (when-not (.isClosed server-socket)
        (let [client (.accept server-socket)
              thread (async/thread (client-loop svc server-info id client))]
          (swap! state-ref assoc-in [:server server-socket :clients id] {:id id
                                                                         :socket client
                                                                         :thread thread})
          (recur (inc id)))))
    (catch SocketException se
      (log/debug-ex se ::server-loop-ex))))

(defn start-server
  [{:keys [state-ref] :as svc}
   build-id
   {:keys [host port]
    :or {port 0}
    :as config}]
  (let [addr (InetAddress/getByName host)
        server-socket (ServerSocket. port 0 addr)
        actual-port (.getLocalPort server-socket)
        server-close (async/chan 1)

        server-info
        {:build-id build-id
         :addr addr
         :server-socket server-socket
         :server-close server-close
         :config config}

        thread
        (async/thread (server-loop svc server-info))]

    (swap! state-ref assoc-in [:servers server-socket]
      {:build-id build-id
       :port actual-port
       :config config
       :server-socket server-socket
       :clients #{}
       :server-close server-close
       :thread thread})

    ;; shutdown server properly when requested
    (let [close-signals
          [server-close ;; single server shutdown
           (:close-chan svc)] ;; service shutdown

          shutdown-server
          (fn []
            (.close server-socket)
            (doseq [{:keys [socket]} (get-in @state-ref [:servers server-socket :clients])]
              (.close socket)))]
      (go (alts! close-signals)
          (shutdown-server)))

    actual-port))

(defn stop-server-on-port
  [{:keys [state-ref] :as svc} port]
  (doseq [chan (->> (:servers @state-ref)
                    (vals)
                    (filter #(= port (:port %)))
                    (map :server-close))]
    (async/close! chan)))

(defn stop-server-for-build
  [{:keys [state-ref] :as svc} build-id]
  (doseq [chan (->> (:servers @state-ref)
                    (vals)
                    (filter #(= build-id (:build-id %)))
                    (map :server-close))]
    (async/close! chan)))

(defn start [repl-system]
  {:state-ref (atom {:servers {}})
   :close-chan (async/chan 1)
   :repl-system repl-system})

(defn stop [{:keys [close-chan state-ref] :as svc}]
  (async/close! close-chan)
  (doseq [{:keys [server-close thread] :as srv} (-> @state-ref :servers vals)]
    (async/close! server-close)
    (<!! thread)))

(comment
  (require '[shadow.cljs.devtools.api :as shadow])
  (def repl-system (:repl-system (shadow/get-runtime!)))

  (def svc (start repl-system))
  svc
  (start-server svc :browser {:port 12345})
  (stop-server-for-build svc :browser)
  (stop svc)
  )
