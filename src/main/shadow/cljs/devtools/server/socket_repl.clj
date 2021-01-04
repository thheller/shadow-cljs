(ns shadow.cljs.devtools.server.socket-repl
  (:require
    [clojure.main :as m]
    [clojure.core.server :as srv]
    [shadow.jvm-log :as log])
  (:import java.net.ServerSocket
           (java.io BufferedWriter OutputStreamWriter InputStreamReader)
           (java.net InetAddress SocketException)
           (clojure.lang LineNumberingPushbackReader)))

(def ^:dynamic *socket* nil)

(defn repl-prompt []
  ;; FIXME: inf-clojure checks when there is a space between \n and =>
  (printf "%s=> " (ns-name *ns*)))

(defn repl-init [{:keys [print] :as config}]

  (require 'shadow.user)
  (in-ns 'shadow.user)

  (when-not (false? print)
    (println "shadow-cljs - REPL - see (help)")
    (println "To quit, type: :repl/quit")))

(defn repl [{:keys [print prompt] :as config}]
  (let [loop-bindings (volatile! {})]
    (m/repl
      :init
      #(repl-init config)

      ;; need :repl/quit support, so not just the default read
      :read
      srv/repl-read

      :print
      (fn [x]
        (cond
          (false? print)
          nil

          (fn? print)
          (print x)

          :else
          (clojure.core/prn x)))

      :prompt
      (cond
        (false? prompt)
        (fn [])

        (fn? prompt)
        prompt

        :else
        repl-prompt)

      :eval
      (fn [form]
        (let [result (eval form)]
          (vreset! loop-bindings (get-thread-bindings))
          result))
      )))

(defmacro ^:private thread
  [^String name daemon & body]
  `(doto (Thread. (fn [] ~@body) ~name)
     (.setDaemon ~daemon)
     (.start)))

(defn connection-loop [config app-ref socket]
  (let [in
        (-> (.getInputStream socket)
            (InputStreamReader.)
            (LineNumberingPushbackReader.))

        out
        (-> (.getOutputStream socket)
            (OutputStreamWriter.)
            (BufferedWriter.))

        app
        (or @app-ref
            (loop [x 0]
              (Thread/sleep 100)
              (or @app-ref
                  (if (> x 10)
                    ::timeout
                    (recur (inc x))))))]

    (if (= ::timeout app)
      (do (.write out "APP TIMEOUT")
          (.close socket))

      (try
        (binding [*in* in
                  *out* out
                  *err* out
                  *socket* socket]

          (repl config))

        (catch SocketException se
          ;; writing to lost connection throws se
          nil)

        (catch Exception e
          (log/warn-ex e ::socket-repl-ex))

        (finally
          ;; try to close but ignore all errors
          (try (.close socket) (catch Throwable t)))))))

(defn start
  ;; FIXME: could probably just use that, it provides all the required hooks
  "basically less generic clojure.repl.server"
  [{:keys [host port]
    :or {host "localhost"
         port 0}
    :as config} app-ref]
  (let [addr
        (InetAddress/getByName host) ;; nil returns loopback

        server-socket
        (ServerSocket. port 0 addr)

        sockets-ref
        (volatile! #{})

        server-thread
        (thread
          (str "shadow-cljs:socket-repl") true
          (try
            (loop []
              (when (not (.isClosed server-socket))
                (try
                  (let [conn (.accept server-socket)]
                    (vswap! sockets-ref conj conn)
                    (thread
                      (str "shadow-cljs:socket-repl-client") false
                      (connection-loop config app-ref conn)
                      (vswap! sockets-ref disj conn)))
                  (catch SocketException _disconnect))
                (recur)))))]

    {:server-thread server-thread
     :server-socket server-socket
     :sockets-ref sockets-ref
     :host host
     :port (.getLocalPort server-socket)}))

(defn stop [{:keys [server-socket server-thread sockets-ref]}]
  (.close server-socket)
  (doseq [s @sockets-ref]
    (.close s))
  (.interrupt server-thread))

(comment
  (def x (start {:port 8201} (future {:app true})))

  (stop x))

