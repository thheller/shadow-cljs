(ns shadow.cljs.devtools.server.socket-repl
  (:require [shadow.repl :as repl]
            [clojure.pprint :refer (pprint)]
            [clojure.main :as m]
            [clojure.string :as str]
            [clojure.core.server :as srv]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.server.repl-api :as repl-api])
  (:import java.net.ServerSocket
           (java.io StringReader PushbackReader PrintStream BufferedWriter OutputStreamWriter InputStreamReader)
           (java.net InetAddress SocketException)
           (clojure.lang LineNumberingPushbackReader)))

(defn repl-prompt []
  (printf "[%d:%d] %s=> " repl/*root-id* repl/*level-id* (ns-name *ns*)))

(def repl-requires
  '[[clojure.repl :refer (source apropos dir pst doc find-doc)]
    [clojure.java.javadoc :refer (javadoc)]
    [clojure.pprint :refer (pp pprint)]
    [shadow.cljs.devtools.server.repl-api :as shadow :refer (help)]])

(defn repl-init []
  (in-ns 'shadow.user)
  (apply require repl-requires)
  (shadow.cljs.devtools.server.repl-api/help))

(defn repl []
  (let [loop-bindings
        (volatile! {})

        {::repl/keys [print] :as root}
        (repl/root)]

    (repl/takeover
      {::repl/lang :clj
       ::repl/get-current-ns
       (fn []
         ;; FIXME: dont return just the string, should contain more info
         (with-bindings @loop-bindings
           (str *ns*)))

       ::repl/read-string
       (fn [s]
         (with-bindings @loop-bindings
           (try
             (let [eof
                   (Object.)

                   result
                   (read
                     {:read-cond true
                      :features #{:clj}
                      :eof eof}
                     (PushbackReader. (StringReader. s)))]
               (if (identical? eof result)
                 {:result eof}
                 {:result :success :value result}))
             (catch Exception e
               {:result :exception
                :e e}
               ))))}

      (m/repl
        :init
        repl-init

        ;; need :repl/quit support, so not just the default read
        :read
        srv/repl-read

        :print
        (fn [x]
          (cond
            print
            (print x)

            ;; print nil?
            ;; (nil? x)
            ;; (println)

            :else
            (clojure.core/println x)))

        :prompt
        repl-prompt

        :eval
        (fn [form]
          (let [result (eval form)]
            (vreset! loop-bindings (get-thread-bindings))
            result))
        ))))

(defmacro ^:private thread
  [^String name daemon & body]
  `(doto (Thread. (fn [] ~@body) ~name)
     (.setDaemon ~daemon)
     (.start)))

(defn connection-loop [config app-promise socket]
  (let [in
        (-> (.getInputStream socket)
            (InputStreamReader.)
            (LineNumberingPushbackReader.))

        out
        (-> (.getOutputStream socket)
            (OutputStreamWriter.)
            (BufferedWriter.))

        app
        (deref app-promise 1000 ::timeout)]

    (if (= ::timeout app)
      (do (.write out "APP TIMEOUT")
          (.close socket))

      (try
        (binding [*in* in
                  *out* out
                  *err* out
                  repl-api/*app* app]

          (repl/enter-root
            {::repl/type :remote
             ::socket socket}
            (repl)))

        (catch SocketException _disconnect)

        (finally
          (.close socket))))))

(defn start
  ;; FIXME: could probably just use that, it provides all the required hooks
  "basically less generic clojure.repl.server"
  [{:keys [host port]
    :or {host "localhost"
         port 8201}
    :as config} app-promise]
  (let [addr
        (InetAddress/getByName host) ;; nil returns loopback

        server-socket
        (ServerSocket. port 0 addr)

        server-thread
        (thread
          (str "shadow-cljs:socket-repl") true
          (try
            (loop []
              (when (not (.isClosed server-socket))
                (try
                  (let [conn (.accept server-socket)]
                    (thread
                      (str "shadow-cljs:socket-repl-client") false
                      (connection-loop config app-promise conn)))
                  (catch SocketException _disconnect))
                (recur)))))]

    {:server-thread server-thread
     :server-socket server-socket
     :host host
     :port port}))

(defn stop [{:keys [server-socket server-thread]}]
  (.close server-socket)
  (.interrupt server-thread))

(comment
  (def x (start {:port 8201} (future {:app true})))

  (stop x))

