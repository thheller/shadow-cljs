(ns shadow.cljs.devtools.server.prepl
  (:require
    [clojure.core.async :as async :refer (go <! <!! >! >!! alts! alt!!)]
    [shadow.cljs.repl :as repl]
    [shadow.cljs.devtools.server.repl-system :as repl-system]
    [shadow.build.warnings :as warnings]
    [shadow.cljs.devtools.api :as shadow]
    [shadow.core-ext :as core-ext]
    [shadow.jvm-log :as log])
  (:import [java.net ServerSocket InetAddress]
           [java.util UUID]
           [java.io OutputStreamWriter BufferedWriter InputStreamReader]))

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

        sessions
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

    ;; FIXME: the client should pick which session it wants
    ;; (send! {:tag :sessions :sessions sessions})

    ;; work loop
    (loop []
      (alt!!
        server-close
        ([_] :close)

        socket-msg
        ([msg]
         (when-not (nil? msg)
           (let [{:keys [error? ex source]} msg]
             (cond
               error?
               (do (send! {:tag :err :msg (str "Failed to read: " ex)})
                   (recur))

               (= ":repl/quit" source)
               :quit

               (= ":cljs/quit" source)
               :quit

               :else
               (do (send! {:tag :read :source source})
                   (recur))))))

        tool-out
        ([msg]
         (when-not (nil? msg)
           (send! {:tag :tool-out :msg msg})
           (recur)))))

    (swap! state-ref update-in [:server server-socket :clients] dissoc client-id)
    (async/close! tool-in)
    (.close socket)))

(defn server-loop
  [{:keys [state-ref] :as svc} {:keys [server-socket] :as server-info}]
  (loop [id 0]
    (when-not (.isClosed server-socket)
      (let [client (.accept server-socket)
            thread (async/thread (client-loop svc server-info id client))]
        (swap! state-ref assoc-in [:server server-socket :clients id] {:id id
                                                                       :socket client
                                                                       :thread thread})
        (recur (inc id))))))

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

(defn stop-server-on-port [svc port])

(defn stop-server-for-build [svc build-id])

(defn start [repl-system]
  {:state-ref (atom {:servers {}})
   :close-chan (async/chan 1)
   :repl-system repl-system})

(defn stop [{:keys [state-ref] :as svc}]
  (doseq [{:keys [close-chan thread] :as srv} (-> @state-ref :servers vals)]
    (async/close! close-chan)
    (<!! thread)))

(comment
  (require '[shadow.cljs.devtools.api :as shadow])
  (def repl-system (:repl-system (shadow/get-runtime!)))

  (def svc (start repl-system))
  svc
  (start-server svc :browser {:port 12345})
  (stop svc)
  )