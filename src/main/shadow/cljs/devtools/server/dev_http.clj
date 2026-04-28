(ns shadow.cljs.devtools.server.dev-http
  "provides a basic static http server per build"
  (:require
    [clojure.java.io :as io]
    [clojure.core.async :as async :refer (>!! <!!)]
    [clojure.spec.alpha :as s]
    [shadow.cljs.devtools.server.sync-db :as sync-db]
    [shadow.fswatch :as fswatch]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs :as-alias m]
    [shadow.http.server :as http-server]
    [shadow.http.push-state :as push-state]
    [clojure.string :as str])
  (:import [java.net URI]
           [javax.net.ssl SSLContext TrustManager X509TrustManager]
           [shadow.http.server HttpHandler ProxyHandler]))

(defn require-var [sym]
  (try
    (require (symbol (namespace sym)))
    (find-var sym)
    (catch Exception e
      (throw (ex-info "failed to require-var by name" {:sym sym} e)))))

(defn make-proxy-handler [{:keys [proxy-url] :as config}]
  (let [uri (URI/create proxy-url)]
    (if-not (str/starts-with? proxy-url "https")
      (ProxyHandler. uri nil (:connect-timeout config 5000))
      (let [;; dev server don't always need to validate certs
            ;; and shouldn't choke on self-signed certs
            trust-managers
            (when (true? (:trust-all-certs config))
              (let [tm-trust-everything
                    (reify X509TrustManager
                      (checkClientTrusted [this chain auth-type])
                      (checkServerTrusted [this chain auth-type])
                      (getAcceptedIssuers [this] nil))]
                (into-array TrustManager [tm-trust-everything])))

            ;; FIXME: I hope this is an actual new context
            ;; not a globally shared default? only really want to disable
            ;; cert checking for servers that chose to, not everything
            ssl-context
            (doto (SSLContext/getInstance "SSL")
              (.init
                nil ;; keymanager use defaults
                trust-managers ;; nil uses defaults, otherwise trust everything
                nil ;; securerandom use defaults
                ))]
        (ProxyHandler. uri ssl-context (:connect-timeout config 5000))))))

(defn start-build-server
  [sys-config sys-bus ssl-context out
   {:keys [proxy-url proxy-predicate port ssl-port host roots handler]
    :or {port 0}
    :as config}]

  (let [http-host
        (or (and (seq host) host)
            (get-in config [:http :host])
            "0.0.0.0")

        handler-var
        (cond
          (nil? handler)
          #'push-state/handle

          :else
          (do
            (or (try
                  (require (symbol (namespace handler)))
                  (find-var handler)
                  (catch Exception e
                    (log/warn-ex e ::handler-load-ex {:http-handler handler})))
                (do (log/warn ::handler-not-found {:http-handler handler})
                    #'push-state/handle))))

        http-info-ref
        (atom {})

        http-handler-fn
        (fn [req]
          (-> req
              (assoc :http @http-info-ref :http-config config :http-roots roots)
              (handler-var)))]

    (try
      (let [req-handler
            []

            req-handler
            (conj req-handler
              (reify HttpHandler
                (handle [this request]
                  (.setResponseHeader request "Access-Control-Allow-Origin" "*"))))

            ;; first try user configured roots
            req-handler
            (reduce
              (fn [req-handler root]
                (cond
                  (str/starts-with? root "classpath:/")
                  (conj req-handler (http-server/classpath-handler (subs root 10)))

                  (str/starts-with? root "classpath:")
                  (conj req-handler (http-server/classpath-handler (str "/" (subs root 10))))

                  :else
                  (let [root-dir (io/file root)]
                    (when-not (.exists root-dir)
                      (io/make-parents (io/file root-dir "index.html")))
                    (conj req-handler (http-server/file-handler root-dir)))))
              req-handler
              (reverse roots))

            ;; then try shipped shadow-cljs resources (favicon)
            req-handler
            (conj req-handler
              (http-server/classpath-handler "/shadow/cljs/devtools/server/dev_http"))

            ;; then try proxy if configured
            req-handler
            (if-not (seq proxy-url)
              req-handler
              (let [^ProxyHandler proxy-handler (make-proxy-handler config)]
                (if-not (qualified-symbol? proxy-predicate)
                  (conj req-handler proxy-handler)

                  ;; :proxy-predicate pointing to function accepting request and returning boolean
                  ;; true if request should use proxy, false will use handler
                  (let [pred-var (require-var proxy-predicate)]
                    (conj req-handler
                      (reify HttpHandler
                        (handle [this request]
                          (when (pred-var request config)
                            (.handle proxy-handler request)))))))))

            ;; and if none of those answered defer to ring handler (push-state default)
            req-handler
            (conj req-handler
              (http-server/ring-handler http-handler-fn))

            http-options
            (-> {:port port
                 :host http-host}
                (cond->
                  (and ssl-context (not (false? (:ssl config))))
                  (assoc :ssl-context ssl-context)))

            http-server
            (when (or (not ssl-context)
                      (and port ssl-port))
              (try
                (http-server/start {:port port :host host} req-handler)
                (catch Exception e
                  (log/warn-ex e ::http-start-ex {:http-options http-options :config config})
                  nil)))

            https-server
            (when ssl-context
              (try
                (http-server/start {:port (or ssl-port port) :host host :ssl-context ssl-context} req-handler)
                (catch Exception e
                  (log/warn-ex e ::http-start-ex {:http-options http-options :config config})
                  nil)))

            file-watchers
            (->> roots
                 (remove #(str/starts-with? % "classpath"))
                 (mapv (fn [root]
                         (let [root-dir (io/file root)]
                           (fswatch/start (:fs-watch sys-config) [root-dir] #{"css"}
                             (fn [updates]
                               (sys-bus/publish! sys-bus ::m/asset-update {:updates (mapv #(str "/" (:name %)) updates)})
                               ))))))

            display-host
            (if (= "0.0.0.0" http-host) "localhost" http-host)

            https-url
            (when https-server
              (format "https://%s:%s" display-host (:port https-server)))

            http-url
            (when http-server
              (format "http://%s:%s" display-host (:port http-server)))]

        (swap! http-info-ref merge {:port (or (:port http-server) (:port https-server))})

        (when https-server
          (log/debug ::https-serve (dissoc https-server :server))
          (>!! out {:type :println
                    :msg (format "shadow-cljs - :dev-http %s serving %s" https-url (pr-str roots))}))

        (when http-server
          (log/debug ::http-serve (dissoc http-server :server))
          (>!! out {:type :println
                    :msg (format "shadow-cljs - :dev-http %s serving %s" http-url (pr-str (or roots proxy-url)))}))



        {:http-url http-url
         :https-url https-url
         :config config
         :file-watchers file-watchers
         :http-server http-server
         :https-server https-server})

      (catch Exception e
        (log/warn-ex e ::start-ex config)
        nil))))

(defn extract-http-config-from-build
  [{:keys [http-root http-port https-port http-host http-resource-root http-handler proxy-url] :as build}]
  (when (or http-port https-port)

    (-> {:roots []}
        (merge (select-keys build [:proxy-rewrite-host-header
                                   :proxy-reuse-x-forwarded
                                   :proxy-max-connection-retries
                                   :proxy-max-request-time
                                   :push-state/headers
                                   :push-state/index]))
        (cond->
          http-port
          (assoc :port http-port)

          http-handler
          (assoc :handler http-handler)

          https-port
          (assoc :ssl-port https-port)

          (seq http-host)
          (assoc :host http-host)

          (seq http-root)
          (update :roots conj http-root)

          (seq http-resource-root)
          (update :roots conj (str "classpath:" http-resource-root))

          (seq proxy-url)
          (assoc :proxy-url proxy-url)))))

(defn desugar-http-config [input]
  (cond
    (not (seq input))
    []

    (map? input)
    (reduce-kv
      (fn [servers port config]
        (let [config
              (cond
                (map? config)
                config

                (string? config)
                {:roots [config]}

                (and (vector? config)
                     (every? string? config))
                {:roots config}

                (qualified-symbol? config)
                {:handler config}

                :else
                (throw (ex-info "invalid value for :dev-http entry" {:key port :value config})))

              config
              (-> config
                  (assoc :port port)
                  (cond->
                    (:root config)
                    (-> (update :roots (fnil conj []) (:root config))
                        (dissoc :root))))]

          (if-not (s/valid? ::server-config config)
            (do (log/warn ::invalid-server-config {:config config})
                servers)
            (conj servers config))))
      []
      input)

    (vector? input)
    input

    :else
    (throw (ex-info "invalid :dev-http value" {:input input}))
    ))

(s/def ::host string?)
(s/def ::port pos-int?)

(defn valid-http-root? [s]
  (and (string? s) (seq s)))

(s/def ::root valid-http-root?)

(s/def ::roots (s/coll-of ::root :kind vector? :min-count 1))

(s/def ::handler qualified-symbol?)

(defn roots-or-handler? [x]
  (or (seq (:roots x))
      (:handler x)
      (:proxy-url x)))

(s/def ::server-config
  (s/and
    (s/keys
      :req-un
      [::port]
      :opt-un
      [::ssl-port
       ::host
       ::proxy-url
       ::ssl-only
       ::handler
       ::roots])
    roots-or-handler?))

(s/def ::server-configs
  (s/coll-of ::server-config :kind vector?))

(defn transform-server-configs [{:keys [builds dev-http] :as config}]
  (let [via-builds
        (->> (vals builds)
             (map (fn [{:keys [devtools] :as build-config}]
                    (extract-http-config-from-build devtools)))
             (remove nil?))

        new-format
        (desugar-http-config dev-http)]

    (-> []
        (into via-builds)
        (into new-format)
        (->> (sort-by :port)
             (into [])))))

(comment
  (clojure.pprint/pprint
    (transform-server-configs
      {:dev-http
       {8000 "default-root"
        8001 ["a"]
        8002 {:ssl-port 8003}
        8004 {:proxy-url "http://localhost:1234"}
        8005 {:root ""} ;; invalid
        8006 {} ;; invalid
        8007 {:handler :foo} ;;invalid
        8008 {:handler 'foo/bar}
        }
       :builds
       {:foo
        {:devtools
         {:http-root "foo-root"}}}})))

(defn start-servers [config sys-bus configs ssl-context out]
  (into [] (map #(start-build-server config sys-bus ssl-context out %)) configs))

(defn stop-servers [{:keys [servers] :as state}]
  (doseq [{:keys [http-server https-server file-watchers] :as srv} servers]
    (doseq [fsw file-watchers]
      (fswatch/stop fsw))
    (when http-server
      (http-server/stop http-server))
    (when https-server
      (http-server/stop https-server)))
  (dissoc state :server :configs))

(defn sync-servers [sync-db servers]
  (sync-db/update! sync-db update ::m/http-server
    (fn [table]
      (reduce-kv
        (fn [table idx {:keys [http-url https-url config]}]
          (assoc table idx {::m/http-server-id idx
                            ::m/http-url http-url
                            ::m/http-config config
                            ::m/https-url https-url}))
        ;; FIXME: don't fully reset table in case these ever receive updates elsewhere?
        {}
        servers))))

(defn start [sys-bus config ssl-context out sync-db]
  (let [sub-chan
        (-> (async/sliding-buffer 1)
            (async/chan))

        sub
        (sys-bus/sub sys-bus ::m/config-watch sub-chan true)

        configs
        (transform-server-configs config)

        state-ref
        (-> {:servers (start-servers config sys-bus configs ssl-context out)
             :configs configs
             :ssl-context ssl-context
             :sub sub
             :sub-chan sub-chan
             :out out}
            (atom))

        loop-chan
        (async/thread
          (loop [configs configs]
            (when-some [{new-config :config} (<!! sub-chan)]
              (let [new-configs (transform-server-configs new-config)]
                (when (not= new-configs configs)
                  (log/debug ::config-change)
                  (>!! out {:type :println
                            :msg "shadow-cljs - HTTP config change, restarting servers"})
                  (swap! state-ref stop-servers)
                  (swap! state-ref assoc
                    :servers (start-servers sys-bus new-configs ssl-context out)
                    :configs new-configs)

                  (sync-servers sync-db (:servers @state-ref)))

                (recur new-configs)))))]

    (sync-servers sync-db (:servers @state-ref))

    (swap! state-ref assoc :loop-chan loop-chan)

    state-ref
    ))

(defn stop [state-ref]
  (let [{:keys [sub-chan loop-chan] :as state} @state-ref]
    (async/close! sub-chan)
    (stop-servers state)
    (async/<!! loop-chan)
    ))