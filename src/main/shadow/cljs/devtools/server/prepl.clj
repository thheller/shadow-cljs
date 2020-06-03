(ns shadow.cljs.devtools.server.prepl
  (:require
    [clojure.core.async :as async :refer (go <! <!! >! >!! alts! alt!!)]
    [shadow.cljs.devtools.api :as shadow]
    [shadow.core-ext :as core-ext]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.server.repl-impl :as repl-impl]
    [shadow.cljs.devtools.server.supervisor :as supervisor])
  (:import [java.net ServerSocket InetAddress SocketException]
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
  [{:keys [state-ref relay supervisor] :as svc}
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

        send!
        (fn send! [msg]
          (doto socket-out
            (.write (core-ext/safe-pr-str msg))
            (.write "\n")
            (.flush)))

        worker
        (supervisor/get-worker supervisor build-id)]

    (if-not worker
      (send! {:tag :err :val (str "The watch for build " build-id " is not running.")})
      (repl-impl/do-repl
        worker
        relay
        socket-in
        server-close
        {:repl-prompt
         (fn repl-prompt [repl-state])

         :repl-read-ex
         (fn repl-read-ex [repl-state ex]
           (send! {:tag :err :val (.getMessage ex)}))

         :repl-result
         (fn repl-result
           [{:keys [ns eval-result read-result] :as repl-state}
            result-as-printed-string]
           (if-not result-as-printed-string
             ;; repl-result is called even in cases there is no result
             ;; so prepl always has a :ret
             (send! {:tag :ret
                     :val "nil"
                     :form (:source read-result)
                     :ns (str ns)
                     :ms (:eval-ms eval-result 0)})
             ;; regular return value
             (send! {:tag :ret
                     :val result-as-printed-string
                     :form (:source read-result)
                     :ns (str ns)
                     :ms (:eval-ms eval-result 0)})))

         :repl-stderr
         (fn repl-stderr [repl-state text]
           (send! {:tag :err
                   :val text}))

         :repl-stdout
         (fn repl-stdout [repl-state text]
           (send! {:tag :out
                   :val text}))
         }))

    (swap! state-ref update-in [:server server-socket :clients] dissoc client-id)
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

(defn start [relay supervisor]
  {:state-ref (atom {:servers {}})
   :close-chan (async/chan 1)
   :relay relay
   :supervisor supervisor})

(defn stop [{:keys [close-chan state-ref] :as svc}]
  (async/close! close-chan)
  (doseq [{:keys [server-close thread] :as srv} (-> @state-ref :servers vals)]
    (async/close! server-close)
    (<!! thread)))

(comment
  (require '[shadow.cljs.devtools.api :as shadow])
  (def relay (:relay (shadow/get-runtime!)))

  (def svc (start relay))
  svc
  (start-server svc :browser {:port 12345})
  (stop-server-for-build svc :browser)
  (stop svc)
  )
