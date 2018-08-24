(ns shadow.cljs.devtools.server.dev-http
  "provides a basic static http server per build"
  (:require
    [clojure.java.io :as io]
    [clojure.core.async :as async :refer (>!! <!!)]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs.devtools.server.system-msg :as sys-msg]
    [shadow.undertow :as undertow]
    [shadow.http.push-state :as push-state]
    ;; FIXME: delay loading ring.* until first request here too
    ;; don't want to AOT these
    [shadow.cljs.devtools.server.ring-gzip :as ring-gzip]
    [ring.middleware.resource :as ring-resource]
    [ring.middleware.file :as ring-file]
    [ring.middleware.file-info :as ring-file-info]
    [ring.middleware.content-type :as ring-content-type])
  (:import [io.undertow.server.handlers.proxy ProxyHandler LoadBalancingProxyClient]
           [java.net URI]))

(defn disable-all-kinds-of-caching [handler]
  ;; this is strictly a dev server and caching is not wanted for anything
  ;; basically emulates having devtools open with "Disable cache" active
  (fn [req]
    (-> req
        (handler)
        (update-in [:headers] assoc
          "cache-control" "max-age=0, no-cache, no-store, must-revalidate"
          "pragma" "no-cache"
          "expires" "0"))))

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
          (let [proxy-client
                (-> (LoadBalancingProxyClient.)
                    (.addHost (URI. proxy-url)))

                proxy-handler
                (-> (ProxyHandler/builder)
                    (.setProxyClient proxy-client)
                    (.setMaxRequestTime 30000)
                    (.build))]

            (fn [{ex :shadow.undertow.impl/exchange :as req}]
              (.handleRequest proxy-handler ex)
              ;; FIXME: cannot return ::undertow/async here
              ;; the ring middleware blows up if this doesn't return a map
              {:status 200
               :body ""
               ::undertow/handled true}))

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
      (let [middleware-fn
            #(-> %
                 ;; some default resources, only used if no file exists
                 ;; currently only contains the CLJS logo as favicon.ico
                 ;; pretty much only doing this because of the annoying
                 ;; 404 chrome devtools show then no icon exists
                 (ring-resource/wrap-resource "shadow/cljs/devtools/server/dev_http")
                 (ring-content-type/wrap-content-type)
                 (cond->
                   (seq http-resource-root)
                   (ring-resource/wrap-resource http-resource-root)

                   root-dir
                   (ring-file/wrap-file root-dir {:allow-symlinks? true
                                                  :index-files? true}))
                 (ring-file-info/wrap-file-info
                   ;; source maps
                   {"map" "application/json"})

                 (ring-gzip/wrap-gzip)
                 (disable-all-kinds-of-caching))

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
                          (undertow/start http-options http-handler-fn middleware-fn)
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
            (if (= "0.0.0.0" http-host) "localhost" http-host)]

        (swap! http-info-ref merge {:port http-port})

        (when https-port
          (>!! out {:type :println
                    :msg (format "shadow-cljs - HTTP server for %s available at https://%s:%s" build-id display-host https-port)}))

        (when http-port
          (>!! out {:type :println
                    :msg (format "shadow-cljs - HTTP server for %s available at http://%s:%s" build-id display-host http-port)}))

        (log/debug ::http-serve (-> server
                                    (dissoc :instance)
                                    (assoc :build-id build-id)))

        {:build-id build-id
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
        (sys-bus/sub sys-bus ::sys-msg/config-watch sub-chan true)

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