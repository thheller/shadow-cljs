(ns shadow.cljs.devtools.server.web.repl
  (:require
    [clojure.core.async :as async :refer (go <! >! thread alt!! <!! >!!)]
    [shadow.jvm-log :as log]
    [clojure.string :as str])
  (:import [java.io Writer InputStreamReader BufferedReader IOException OutputStreamWriter BufferedWriter]
           [java.net Socket NetworkInterface]))

(defmulti ws-process*
  (fn [client-state msg]
    (:tag msg))
  :default ::default)

(defmethod ws-process* ::default
  [client-state msg]
  (log/warn ::unknown-msg {:msg msg}))

(defmethod ws-process* :socket-out
  [{::keys [socket-out out-fn] :as client-state}
   {:keys [text] :as msg}]

  (.write socket-out (str text "\n"))
  (.flush socket-out)

  client-state
  )

(defn ws-process [{::keys [out-fn] :as state} msg]
  (try
    (let [next-state (ws-process* state msg)]
      (when-not (and (map? next-state) (::client-state next-state))
        (log/warn ::invalid-ws-result {:msg msg})
        (throw (ex-info "invalid ws-process result" {})))
      next-state)
    (catch Exception ex
      (log/warn-ex ex ::process-ws-ex {:msg msg})
      (out-fn {:tag :ex :ex (Throwable->map ex) :ns (str *ns*)})
      state
      )))

;; would prefer prepl but can't force users to use 1.10 alpha releases
(defn repl-connect [{::keys [out-fn] :as ctx}]
  (let [socket
        (Socket. "localhost" (get-in ctx [:socket-repl :port]))

        socket-in
        (.getInputStream socket)

        socket-out
        (-> (.getOutputStream socket)
            (OutputStreamWriter.)
            (BufferedWriter.))

        socket-in-thread
        (thread
          (with-open [^java.io.Reader in (-> socket-in InputStreamReader. BufferedReader.)]
            (try
              (loop [buf (char-array 1024)]
                (let [len (.read in buf)]
                  (when (not= -1 len)
                    (when (pos? len)
                      (out-fn {:tag :socket-in :text (String. buf 0 len)}))
                    (recur buf))))
              (catch Exception e
                (log/debug-ex e ::repl-out)))))]

    (assoc ctx
      ::socket socket
      ::socket-in socket-in
      ::socket-in-thread socket-in-thread
      ::socket-out socket-out)))

(defn repl-cleanup [{::keys [socket] :as ctx}]
  (when socket
    (.close socket)))

(defn repl-ws
  [{:keys [server-secret ring-request] :as ctx}]
  (let [{:keys [ws-in ws-out]}
        ring-request

        cookie
        (get-in ctx [:ring-request :headers "cookie"])

        ex
        (get-in ctx [:ring-request :shadow.undertow.impl/exchange])

        addr
        (-> ex (.getSourceAddress) (.getAddress))

        is-local?
        (some #(= addr %)
          (for [ni (enumeration-seq (NetworkInterface/getNetworkInterfaces))
                naddr (enumeration-seq (.getInetAddresses ni))]
            naddr))]

    ;; paranoid security checks since this can eval ...
    ;; only allow local connections since this is meant for the dev UI
    (cond
      (not is-local?)
      {:status 403
       :body "only local connections allowed."}

      ;; the page initially sets a SameSite/HttpOnly cookie which the websocket connect will then include
      ;; this is to prevent someone from using a html file and connect to wss://localhost:9630... in that page
      ;; the connection would still be local but the page would have full access to the socket
      (not (and (seq cookie)
                (str/includes? cookie server-secret)))
      {:status 403
       :body "missing secret."}

      ;; FIXME: safe enough? not sure what else to do
      :else
      (let [out-fn #(>!! ws-out %)]
        (thread
          ;; FIXME: send interesting infos to the client
          (>!! ws-out {:tag :welcome})

          (log/debug ::loop-start)

          (let [init-state
                (-> (assoc ctx
                      ::client-state true
                      ::ws-in ws-in
                      ::ws-out ws-out
                      ::out-fn out-fn)
                    (repl-connect))

                exit-state
                (loop [state init-state]
                  (when-some [msg (<!! ws-in)]
                    (-> state
                        (ws-process msg)
                        (recur))))]

            (repl-cleanup exit-state)
            (log/debug ::loop-end)))))))