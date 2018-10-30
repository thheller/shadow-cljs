(ns shadow.cljs.devtools.server.dev-http
  "provides a basic static http server per build"
  (:require
    [clojure.java.io :as io]
    [clojure.core.async :as async :refer (>!! <!!)]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs.model :as m]
    [shadow.undertow :as undertow]
    [shadow.http.push-state :as push-state]
    [clojure.string :as str])
  (:import [io.undertow.server.handlers.proxy ProxyHandler LoadBalancingProxyClient]
           [java.net URI]
           [org.xnio Xnio OptionMap]
           [io.undertow.protocols.ssl UndertowXnioSsl]
           [org.xnio.ssl XnioSsl]))

(defn make-proxy-handler [proxy-url]
  (let [proxy-client
        (LoadBalancingProxyClient.)

        proxy-handler
        (-> (ProxyHandler/builder)
            (.setProxyClient proxy-client)
            (.setMaxRequestTime 30000)
            (.build))]

    (if-not (str/starts-with? proxy-url "https")
      (.addHost proxy-client (URI. proxy-url))
      (let [xnio
            (Xnio/getInstance)

            xnio-ssl
            (UndertowXnioSsl. xnio OptionMap/EMPTY)]

        (.addHost proxy-client (URI. proxy-url) nil ^XnioSsl xnio-ssl)))

    (fn [{ex :shadow.undertow.impl/exchange :as req}]
      (.handleRequest proxy-handler ex)
      ;; FIXME: cannot return ::undertow/async here
      ;; the ring middleware blows up if this doesn't return a map
      {:status 200
       :body ""
       ::undertow/handled true})))

(defn start-build-server
  [config ssl-context out
   {:keys [build-id proxy-url http-root http-port http-host http-resource-root http-handler]
    :or {http-port 0}
    :as config}]

  (let [root-dir
        (when (seq http-root)
          (io/file http-root))

        http-host
        (or (and (seq http-host) http-host)
            (get-in config [:http :host])
            "0.0.0.0")

        http-handler-var
        (cond
          ;; FIXME: there seems to be no proper way to close the proxy-client instance?
          (seq proxy-url)
          (make-proxy-handler proxy-url)


          (nil? http-handler)
          #'push-state/handle

          :else
          (do
            (or (try
                  (require (symbol (namespace http-handler)))
                  (find-var http-handler)
                  (catch Exception e
                    (log/warn-ex e ::handler-load-ex {:http-handler http-handler})))
                (do (log/warn ::handler-not-found {:http-handler http-handler})
                    #'push-state/handle))))

        http-info-ref
        (atom {})

        http-handler-fn
        (fn [req]
          (-> req
              (assoc :http-root root-dir
                     :http @http-info-ref
                     :build-id build-id
                     :devtools config)
              (http-handler-var)))]

    (when (and root-dir (not (.exists root-dir)))
      (io/make-parents (io/file root-dir "index.html")))

    (try
      (let [req-handler
            [::undertow/classpath {:root "shadow/cljs/devtools/server/dev_http"}
             [::undertow/blocking
              [::undertow/ring {:handler-fn http-handler-fn}]]]

            ;; serve resource before handler
            req-handler
            (if-not (seq http-resource-root)
              req-handler
              [::undertow/classpath {:root http-resource-root} req-handler])

            ;; serve files before resource or handler
            req-handler
            (if-not root-dir
              req-handler
              [::undertow/file {:root-dir root-dir} req-handler])

            handler-config
            [::undertow/disable-cache
             [::undertow/ws-upgrade
              [::undertow/ws-ring {:handler-fn http-handler-fn}]
              [::undertow/compress {} req-handler]]]

            http-options
            (-> {:port http-port
                 :host http-host}
                (cond->
                  ssl-context
                  (assoc :ssl-context ssl-context)))

            {:keys [http-port https-port] :as server}
            (loop [{:keys [port] :as http-options} http-options
                   fails 0]
              (let [srv (try
                          (undertow/start http-options handler-config)
                          (catch Exception e
                            (log/warn-ex e ::http-start-ex {:build-id build-id
                                                            :http-options http-options})
                            nil))]
                (cond
                  (some? srv)
                  srv

                  (or (zero? port) (> fails 3))
                  (throw (ex-info "gave up trying to start server" {}))

                  :else
                  (recur (update http-options :port inc) (inc fails))
                  )))

            display-host
            (if (= "0.0.0.0" http-host) "localhost" http-host)

            https-url
            (when https-port
              (format "https://%s:%s" display-host https-port))

            http-url
            (when http-port
              (format "http://%s:%s" display-host http-port))]

        (swap! http-info-ref merge {:port http-port})

        (when https-port
          (>!! out {:type :println
                    :msg (format "shadow-cljs - HTTP server for %s available at %s" build-id https-url)}))

        (when http-port
          (>!! out {:type :println
                    :msg (format "shadow-cljs - HTTP server for %s available at %s" build-id http-url)}))

        (log/debug ::http-serve (-> server
                                    (dissoc :instance)
                                    (assoc :build-id build-id)))

        {:build-id build-id
         :http-url http-url
         :https-url https-url
         :instance server})

      (catch Exception e
        (log/warn-ex e ::start-ex config)
        nil))))

(defn get-server-configs []
  (let [{:keys [builds] :as config}
        (config/load-cljs-edn!)]

    (->> (vals builds)
         (map (fn [{:keys [build-id devtools] :as build-config}]
                (when (contains? devtools :http-root)
                  (assoc devtools :build-id build-id))))
         (remove nil?)
         (into []))))

(comment
  (get-server-configs))

(defn start-servers [config ssl-context out]
  (let [configs
        (get-server-configs)

        servers
        (into [] (map #(start-build-server config ssl-context out %)) configs)]

    {:servers servers
     :configs configs}
    ))

(defn stop-servers [{:keys [servers] :as state}]
  (doseq [{:keys [instance] :as srv} servers
          :when instance]
    (undertow/stop instance))
  (dissoc state :server :configs))

(defn start [sys-bus config ssl-context out]
  (let [sub-chan
        (-> (async/sliding-buffer 1)
            (async/chan))

        sub
        (sys-bus/sub sys-bus ::m/config-watch sub-chan true)

        state-ref
        (-> (start-servers config ssl-context out)
            (assoc :ssl-context ssl-context
                   :sub sub
                   :sub-chan sub-chan
                   :out out)
            (atom))

        loop-chan
        (async/thread
          (loop [config config]
            (when-some [new-config (<!! sub-chan)]
              (let [configs (get-server-configs)]
                (when (not= configs (:configs @state-ref))
                  (log/debug ::config-change)
                  (>!! out {:type :println
                            :msg "shadow-cljs - dev http config change, restarting servers"})
                  (swap! state-ref stop-servers)
                  (swap! state-ref merge (start-servers new-config ssl-context out))))
              (recur new-config))))]

    (swap! state-ref assoc :loop-chan loop-chan)

    state-ref
    ))

(defn stop [state-ref]
  (let [{:keys [sub-chan loop-chan] :as state} @state-ref]
    (async/close! sub-chan)
    (stop-servers state)
    (async/<!! loop-chan)
    ))