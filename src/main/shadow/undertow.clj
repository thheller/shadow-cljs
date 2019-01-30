(ns shadow.undertow
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.core.async :as async :refer (go alt! <! >!)]
            [clojure.core.async.impl.protocols :as async-prot]
            [shadow.jvm-log :as log]
            [shadow.undertow.impl :as impl]
            [clojure.edn :as edn]
            [shadow.core-ext :as core-ext])
  (:import (io.undertow Undertow Handlers UndertowOptions)
           (io.undertow.websockets WebSocketConnectionCallback)
           (io.undertow.server.handlers BlockingHandler)
           (io.undertow.server HttpHandler)
           (shadow.undertow WsTextReceiver ShadowResourceHandler)
           (io.undertow.websockets.core WebSockets)
           (javax.net.ssl SSLContext KeyManagerFactory)
           (java.io FileInputStream File)
           (java.security KeyStore)
           [org.xnio ChannelListener Xnio OptionMap]
           [java.nio.channels ClosedChannelException]
           [io.undertow.util AttachmentKey MimeMappings Headers]
           [io.undertow.server.handlers.resource PathResourceManager ClassPathResourceManager]
           [io.undertow.predicate Predicates Predicate]
           [io.undertow.server.handlers.encoding EncodingHandler ContentEncodingRepository GzipEncodingProvider DeflateEncodingProvider]
           [io.undertow.server.handlers.proxy LoadBalancingProxyClient ProxyHandler]
           [java.net URI]
           [io.undertow.protocols.ssl UndertowXnioSsl]
           [org.xnio.ssl XnioSsl]))

(defn ring* [handler-fn]
  (reify
    HttpHandler
    (handleRequest [_ exchange]
      (let [req (impl/exchange->ring exchange)
            res (handler-fn req)]
        (when (and (map? res) (not (::handled res)))
          (impl/ring->exchange exchange req res))
        ))))

(defn ring
  "blocking ring handler"
  [handler-fn]
  (-> (ring* handler-fn)
      (BlockingHandler.)))

(defn websocket? [{::keys [ws] :as req}]
  ws)

;; unfortunately the exchange field is private
;; and I'm too lazy to write another exchange->ring fn
(def ws-exchange-field
  (doto (-> (Class/forName "io.undertow.websockets.spi.AsyncWebSocketHttpServerExchange")
            (.getDeclaredField "exchange"))
    (.setAccessible true)))

(defn ws->ring [ex channel]
  (-> (impl/exchange->ring (.get ws-exchange-field ex))
      (assoc ::channel channel)))


(defn handler? [x]
  (and x (instance? HttpHandler x)))

(declare build)

(defmulti build* (fn [state config] (first config)) :default ::default)

(defmethod build* ::default [state [id :as config]]
  (throw (ex-info (format "unknown handler: %s" id) {:config config})))

(defmethod build* ::ws-upgrade [state [id upgrade-next next :as config]]
  (when-not (and (vector? upgrade-next)
                 (vector? next))
    (throw (ex-info "ws-upgrade expects 2 paths" {:config config})))

  (let [{upgrade-handler :handler :as state}
        (build state upgrade-next)

        {req-handler :handler :as state}
        (build state next)]

    (assert (handler? upgrade-handler))
    (assert (handler? req-handler))

    (assoc state
      ;; default websocket handler tries to handshake every single request
      ;; which seems like a bit of overkill
      :handler
      (reify HttpHandler
        (handleRequest [this x]
          (if (= "websocket" (-> (.getRequestHeaders x) (.getFirst "Upgrade")))
            (.handleRequest upgrade-handler x)
            (.handleRequest req-handler x)))))))

(defmethod build* ::classpath
  [{:keys [mime-mappings] :as state} [id props next :as config]]
  (when-not (and (vector? next)
                 (map? props))
    (throw (ex-info "classpath expects props and next" {:config config})))

  (let [{:keys [root classloader]}
        props

        _ (assert (seq root))

        classloader
        (or classloader
            (-> (Thread/currentThread)
                (.getContextClassLoader)))

        {next :handler :as state}
        (build state next)

        rc-manager
        (ClassPathResourceManager. classloader root)

        handler
        (doto (ShadowResourceHandler. rc-manager next)
          (.setMimeMappings mime-mappings))]

    (-> state
        (update :managers conj rc-manager)
        (assoc :handler handler))))

(defmethod build* ::file [{:keys [mime-mappings] :as state} [id props next]]
  (assert (vector? next))
  (assert (map? props))

  (let [{:keys [^File root-dir]}
        props

        _ (assert (instance? File root-dir))
        _ (assert (.exists root-dir))
        _ (assert (.isDirectory root-dir))

        {next :handler :as state}
        (build state next)

        rc-manager
        (-> (PathResourceManager/builder)
            (.setBase (-> root-dir (.getAbsoluteFile) (.toPath)))
            ;; FIXME: should probably make these configurable
            (.setFollowLinks true)
            ;; must not be nil, empty == followAll
            (.setSafePaths (into-array String []))
            ;; handle both junction and symlink on windows, refer to SystemUtils in Apache commons-lang3
            (.setCaseSensitive (not (.startsWith (System/getProperty "os.name") "Windows")))
            (.build))

        handler
        (doto (ShadowResourceHandler. rc-manager next)
          (.setMimeMappings mime-mappings)
          (.setCachable (Predicates/falsePredicate))
          (.setAllowed (Predicates/truePredicate)))]

    (-> state
        (update :managers conj rc-manager)
        (assoc :handler handler)
        )))

(defmethod build* ::soft-cache [state [id next]]
  (assert (vector? next))

  (let [{next :handler :as state}
        (build state next)

        handler
        (reify
          HttpHandler
          (handleRequest [_ ex]
            (-> ex
                (.getResponseHeaders)
                ;; minimal caching headers that force the browser the revalidate
                ;; undertow will respond with 304 Not Modified checking If-Modified-Since
                (.add Headers/CACHE_CONTROL "private, no-cache"))
            (.handleRequest next ex)))]
    (assoc state :handler handler)))

(defmethod build* ::disable-cache [state [id next]]
  (assert (vector? next))

  (let [{next :handler :as state}
        (build state next)]
    (assoc state :handler (Handlers/disableCache next))))

(defmethod build* ::blocking [state [id next]]
  (assert (vector? next))

  (let [{next :handler :as state}
        (build state next)]
    (assoc state :handler (BlockingHandler. next))))

(def compressible-types
  ["text/html"
   "text/css"
   "text/plain"
   "text/javascript"
   "application/javascript"
   "application/json"
   "application/json+transit"
   "application/edn"])

(defmethod build* ::compress [state [id props next]]
  (assert (vector? next))

  (let [{next :handler :as state}
        (build state next)

        compress-predicate
        (reify
          Predicate
          (resolve [_ exchange]
            (let [headers
                  (.getResponseHeaders exchange)

                  content-type
                  (.getFirst headers Headers/CONTENT_TYPE)]

              (if-not content-type
                false
                (boolean (some #(str/starts-with? content-type %) compressible-types))
                ))))

        cer
        (doto (ContentEncodingRepository.)
          (.addEncodingHandler "gzip" (GzipEncodingProvider.) 100 compress-predicate)
          (.addEncodingHandler "deflate" (DeflateEncodingProvider.) 10 compress-predicate))

        gzip-handler
        (EncodingHandler. next cer)]

    (assoc state :handler gzip-handler)))

(defmethod build* ::proxy [state [id props & next :as config]]
  (when (seq next)
    (throw (ex-info "proxy doesn't support nested undertow handlers" {:config config})))
  (assert (map? props))

  (let [{:keys [proxy-url]}
        props

        proxy-client
        (LoadBalancingProxyClient.)

        proxy-handler
        (-> (ProxyHandler/builder)
            (.setProxyClient proxy-client)
            (.setMaxConnectionRetries (get props :proxy-max-connection-retries 1))
            (.setMaxRequestTime (get props :proxy-max-request-time 30000))
            (.setRewriteHostHeader (get props :proxy-rewrite-host-header true))
            (.setReuseXForwarded (get props :proxy-reuse-x-forwarded false))
            (.build))]

    (if-not (str/starts-with? proxy-url "https")
      (.addHost proxy-client (URI. proxy-url))
      (let [xnio
            (Xnio/getInstance)

            xnio-ssl
            (UndertowXnioSsl. xnio OptionMap/EMPTY)]

        (.addHost proxy-client (URI. proxy-url) nil ^XnioSsl xnio-ssl)))

    (-> state
        (assoc :handler proxy-handler
               ;; FIXME: not sure we actually need this. no way to shut this down anyways
               :proxy-client proxy-client))))

(defmethod build* ::ring [state [id props & next :as config]]
  (when (seq next)
    (throw (ex-info "ring doesn't support nested undertow handlers" {:config config})))

  (let [{:keys [handler-fn]} props

        handler
        (reify
          HttpHandler
          (handleRequest [_ exchange]
            (let [req (impl/exchange->ring exchange)
                  res (handler-fn req)]
              (when (and (map? res) (not (::handled res)))
                (impl/ring->exchange exchange req res))
              )))]

    (assoc state :handler handler)))

(defonce WS-LOOP (AttachmentKey/create Object))
(defonce WS-IN (AttachmentKey/create Object))
(defonce WS-OUT (AttachmentKey/create Object))

(defmethod build* ::ws-ring [state [id props & next :as config]]
  (when (seq next)
    (throw (ex-info "ring doesn't support nested undertow handlers" {:config config})))

  (let [{:keys [handler-fn]} props

        ws-handler
        (Handlers/websocket
          (reify
            WebSocketConnectionCallback
            (onConnect [_ exchange channel]
              (let [ws-in (.getAttachment exchange WS-IN)
                    ws-out (.getAttachment exchange WS-OUT)
                    ws-loop (.getAttachment exchange WS-LOOP)

                    handler-fn
                    (fn [channel msg]
                      (if-not (some? msg)
                        (async/close! ws-in)
                        (async/put! ws-in msg)))

                    close-task
                    (reify ChannelListener
                      (handleEvent [this ignored-event]
                        (async/close! ws-in)
                        (async/close! ws-out)))]

                (.. channel (addCloseTask close-task))
                (.. channel (getReceiveSetter) (set (WsTextReceiver. handler-fn)))
                (.. channel (resumeReceives))

                (go (loop []
                      ;; try to send remaining messages before disconnect
                      ;; if loop closes after putting something on ws-out
                      (alt! :priority true
                        ws-out
                        ([msg]
                          (if (nil? msg)
                            ;; when out closes, also close in
                            (async/close! ws-in)
                            ;; try to send message, close everything if that fails
                            (do (try
                                  (WebSockets/sendTextBlocking msg channel)
                                  ;; just ignore sending to a closed channel
                                  (catch ClosedChannelException e
                                    (async/close! ws-in)
                                    (async/close! ws-out)))
                                (recur))))

                        ws-loop
                        ([_]
                          (.close exchange)
                          ;; probably already closed, just in case
                          (async/close! ws-out)
                          (async/close! ws-in)
                          ))))

                ))))

        handler
        (ring*
          (fn [{::impl/keys [exchange] :as ring-request}]
            (let [ws-req (assoc ring-request ::ws true)

                  {:keys [ws-in ws-out ws-loop] :as res}
                  (handler-fn ws-req)]

              ;; expecting map with :ws-loop :ws-in :ws-out keys
              (if (and (satisfies? async-prot/ReadPort ws-loop)
                       (satisfies? async-prot/ReadPort ws-in)
                       (satisfies? async-prot/ReadPort ws-out))
                (do (.putAttachment exchange WS-LOOP ws-loop)
                    (.putAttachment exchange WS-IN ws-in)
                    (.putAttachment exchange WS-OUT ws-out)
                    (.handleRequest ws-handler exchange)
                    ::async)

                ;; didn't return a loop. respond without upgrade.
                res
                ))))]

    (assoc state :handler handler)))

(defn build [state config]
  {:pre [(vector? config)
         (map? state)]}
  (build*
    (assoc state :managers [])
    (into [] (remove nil?) config)))


(defn close-handlers [{:keys [managers] :as state}]
  (doseq [mgr managers]
    (.close mgr)))

(defn make-ssl-context [ssl-config]
  (let [key-manager
        (KeyManagerFactory/getInstance
          (KeyManagerFactory/getDefaultAlgorithm))

        key-store
        (KeyStore/getInstance
          (KeyStore/getDefaultType))

        pw
        (.toCharArray (get ssl-config :password "shadow-cljs"))]

    (with-open [fs (FileInputStream. (get ssl-config :keystore "ssl/keystore.jks"))]
      (.load key-store fs pw))

    (.init key-manager key-store pw)

    (doto (SSLContext/getInstance "TLS")
      (.init (.getKeyManagers key-manager) nil nil)
      )))

(defn start
  [{:keys [port host ssl-port ssl-context] :or {host "0.0.0.0"} :as config} handler-config]
  (let [mime-mappings
        (-> (MimeMappings/builder)
            ;; FIXME: make this configurable?
            (.addMapping "map" "application/json")
            (.addMapping "edn" "application/edn")
            (.addMapping "wasm" "application/wasm")
            (.build))

        handler-state
        {:mime-mappings mime-mappings}

        {:keys [handler] :as handler-state}
        (build handler-state handler-config)

        instance
        (doto (-> (Undertow/builder)
                  (cond->
                    ;; start http listener when no ssl-context is set
                    ;; or if ssl-port is set in addition to port
                    (or (not ssl-context)
                        (and port ssl-port))
                    (.addHttpListener port host)

                    ;; listens in port unless ssl-port is set
                    ssl-context
                    (-> (.setServerOption UndertowOptions/ENABLE_HTTP2 true)
                        (.addHttpsListener (or ssl-port port) host ssl-context)))
                  (.setHandler handler)
                  (.build))
          (.start))]

    (reduce
      (fn [x listener]
        (assoc x (keyword (str (.getProtcol listener) "-port")) (-> listener (.getAddress) (.getPort))))
      {:instance instance
       :handler-state handler-state
       :config config}
      (.getListenerInfo instance))))

(defn stop [{:keys [instance handler-state] :as srv}]
  (.stop instance)
  (close-handlers handler-state))

(comment
  (require '[ring.middleware.file :as ring-file])
  (require '[ring.middleware.file-info :as ring-file-info])
  (require '[ring.middleware.content-type :as ring-content-type])
  (require '[shadow.cljs.devtools.server.ring-gzip :as ring-gzip])

  (defn test-ring [req]
    (if-not (websocket? req)
      {:status 200
       :body "hello world"}

      (let [{:keys [ws-in ws-out]} req]
        (go (loop []
              (when-let [msg (<! ws-in)]
                (prn [:ws-echo msg])
                (>! ws-out msg)
                (recur)))
            (prn [:ws-closed])
            ))))

  (def x (start
           {:host "localhost"
            :port 8800
            :ssl-port 8801
            :ssl-context (make-ssl-context {})}
           [::ring {:hander-fn
                    (-> test-ring
                        (ring-content-type/wrap-content-type)
                        (ring-file/wrap-file
                          (io/file "out" "demo-browser" "public")
                          {:allow-symlinks? true
                           :index-files? true})
                        (ring-file-info/wrap-file-info
                          ;; source maps
                          {"map" "application/json"})
                        (ring-gzip/wrap-gzip))}]))

  (prn x)

  (-> (:instance x)
      (.getListenerInfo)
      (first)
      (.getAddress)
      (.getPort))

  (stop x))
