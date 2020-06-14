(ns shadow.cljs.devtools.server.relay-tcp
  (:require [shadow.jvm-log :as log]
            [clojure.core.async :as async :refer (>!! <!!)]
            [shadow.remote.relay.api :as relay]
            [clojure.tools.reader.edn :as edn]
            [shadow.cljs.devtools.api :as api])
  (:import [java.net SocketException ServerSocket InetAddress]
           [java.io BufferedWriter InputStreamReader OutputStreamWriter]
           [clojure.lang LineNumberingPushbackReader]))


(defmacro ^:private thread
  [^String name daemon & body]
  `(doto (Thread. (fn [] ~@body) ~name)
     (.setDaemon ~daemon)
     (.start)))

(defn connection-loop [relay config socket]
  (let [socket-in
        (-> (.getInputStream socket)
            (InputStreamReader.)
            (LineNumberingPushbackReader.))

        out
        (-> (.getOutputStream socket)
            (OutputStreamWriter.)
            (BufferedWriter.))

        to-relay
        (async/chan 128)

        from-relay
        (async/chan 128)

        relay-close
        (relay/connect relay to-relay from-relay {:type :tcp-client})]

    (thread
      (str "shadow-cljs:tcp-relay:client-read")
      (let [EOF (Object.)]
        (try
          (loop []
            (let [res (edn/read {:eof EOF} socket-in)]
              (if (identical? res EOF)
                (do (async/close! to-relay)
                    (async/close! from-relay))
                (when (>!! to-relay res)
                  (recur)))))
          (catch Exception e
            (log/debug-ex e ::read-ex)))))

    (loop []
      (when-some [msg (<!! from-relay)]
        (when (try
                (.write out (str (pr-str msg) "\n"))
                (.flush out)
                true
                (catch SocketException se
                  ;; writing to lost connection throws se
                  nil)
                (catch Exception e
                  (log/warn-ex e ::socket-repl-ex)
                  nil))
          (recur))))

    (.close socket)))

(defn start
  [relay
   {:keys [host port]
    :or {host "localhost"
         port 0}
    :as config}]
  (let [addr
        (InetAddress/getByName host) ;; nil returns loopback

        server-socket
        (ServerSocket. port 0 addr)

        sockets-ref
        (atom #{})

        server-thread
        (thread
          (str "shadow-cljs:tcp-relay:accept") true
          (try
            (loop []
              (when (not (.isClosed server-socket))
                (try
                  (let [conn (.accept server-socket)]
                    (swap! sockets-ref conj conn)
                    (thread
                      (str "shadow-cljs:tcp-relay:client-loop") false
                      (connection-loop relay config conn)
                      (swap! sockets-ref disj conn)))
                  (catch SocketException _disconnect))
                (recur)))))]

    {:server-thread server-thread
     :server-socket server-socket
     :sockets-ref sockets-ref
     :relay relay
     :host host
     :port (.getLocalPort server-socket)}))

(defn stop [{:keys [server-socket server-thread sockets-ref]}]
  (.close server-socket)
  (doseq [s @sockets-ref]
    (.close s))
  (.interrupt server-thread))

(comment
  (require '[shadow.cljs.devtools.api :as api])
  (def x (start (:relay (api/get-runtime!)) {:port 8201}))

  (stop x))
