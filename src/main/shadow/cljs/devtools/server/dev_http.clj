(ns shadow.cljs.devtools.server.dev-http
  "provides a basic static http server per build"
  (:require
    [clojure.java.io :as io]
    [clojure.core.async :as async :refer (>!! <!!)]
    [clojure.spec.alpha :as s]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs.model :as m]
    [shadow.undertow :as undertow]
    [shadow.http.push-state :as push-state]
    [clojure.string :as str])
  (:import [io.undertow.server HttpHandler ExchangeCompletionListener]
           [shadow.undertow ShadowResourceHandler]
           [java.net BindException]))

(defmethod undertow/build* ::file-recorder [state [id {:keys [on-request] :as props} next]]
  (assert (vector? next))

  (let [{next :handler :as state}
        (undertow/build state next)

        completion-listener
        (reify
          ExchangeCompletionListener
          (exchangeEvent [_ exchange next]
            (when-let [rc (.getAttachment exchange ShadowResourceHandler/RESOURCE_KEY)]
              (when-let [file (.getFile rc)]
                (let [uri (.getRequestPath exchange)]
                  (on-request uri file))))
            (.proceed next)))

        record-handler
        (reify
          HttpHandler
          (handleRequest [_ exchange]
            (.addExchangeCompleteListener exchange completion-listener)
            (.handleRequest next exchange)))]

    (assoc state :handler record-handler)))

(defn require-var [sym]
  (try
    (require (symbol (namespace sym)))
    (find-var sym)
    (catch Exception e
      (throw (ex-info "failed to require-var by name" {:sym sym} e)))))

(defn start-build-server
  [sys-bus config ssl-context out
   {:keys [proxy-url proxy-predicate port host roots handler]
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
            [::undertow/classpath {:root "shadow/cljs/devtools/server/dev_http"}
             [::undertow/blocking
              [::undertow/ring {:handler-fn http-handler-fn}]]]

            req-handler
            (cond
              (not (seq proxy-url))
              req-handler

              ;; proxy-url but no proxy-predicate, proxy everything
              (not proxy-predicate)
              [::undertow/strip-secure-cookies
               [::undertow/proxy config]]

              ;; proxy-url + proxy-predicate, let predicate decide what to proxy
              ;; should be symbol pointing to function accepting undertow exchange and returning boolean
              ;; true if request should use proxy, false will use handler
              (qualified-symbol? proxy-predicate)
              (let [pred-var (require-var proxy-predicate)]
                [::undertow/predicate-match
                 {:predicate-fn (fn [ex]
                                  (pred-var ex config))}
                 [::undertow/strip-secure-cookies
                  [::undertow/proxy config]]
                 req-handler])

              :else
              (throw (ex-info "invalid :proxy-predicate value" {:val proxy-predicate})))

            req-handler
            (reduce
              (fn [req-handler root]
                (if (str/starts-with? root "classpath:")
                  [::undertow/classpath {:root (subs root 10)} req-handler]

                  (let [root-dir (io/file root)]
                    (when-not (.exists root-dir)
                      (io/make-parents (io/file root-dir "index.html")))
                    [::undertow/file {:root-dir root-dir} req-handler])))
              req-handler
              (reverse roots))

            files-used-ref
            (atom {})

            file-request-fn
            (fn [path file]
              ;; FIXME: maybe add support for images
              (when (str/ends-with? path ".css")
                (let [key [path file]]
                  (when-not (contains? @files-used-ref key)
                    ;; doesn't matter if using timestamp of last modified of file
                    ;; we only want to reload it when it was changed after the access
                    ;; but don't always record last access since there may be multiple clients
                    (swap! files-used-ref assoc key (System/currentTimeMillis))))))

            handler-config
            [::file-recorder {:on-request file-request-fn}
             [::undertow/soft-cache
              [::undertow/ws-upgrade
               [::undertow/ws-ring {:handler-fn http-handler-fn}]
               [::undertow/compress {} req-handler]]]]

            http-options
            (-> {:port port
                 :host http-host}
                (cond->
                  (and ssl-context (not (false? (:ssl config))))
                  (assoc :ssl-context ssl-context)))

            {:keys [http-port https-port] :as server}
            (loop [{:keys [port] :as http-options} http-options
                   fails 0]
              (let [srv (try
                          (undertow/start http-options handler-config)
                          (catch Exception e
                            (cond
                              (instance? BindException (.getCause e))
                              (log/warn :shadow.cljs.devtools.server/tcp-port-unavailable {:port port})

                              :else
                              (log/warn-ex e ::http-start-ex {:http-options http-options :config config}))
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
              (format "http://%s:%s" display-host http-port))

            file-watch-ref
            (atom true)

            file-watch-fn
            (fn []
              (while @file-watch-ref
                (let [changed
                      (reduce-kv
                        (fn [changed key last-access]
                          (let [[path file] key]
                            (if-not (.exists file)
                              ;; deleted files can not be reloaded, no need to notify anyone
                              (do (swap! files-used-ref dissoc key)
                                  changed)
                              (let [new-mod (.lastModified file)]
                                (if-not (> new-mod last-access)
                                  changed
                                  (do (swap! files-used-ref assoc key new-mod)
                                      (conj changed path)))))))

                        #{}
                        @files-used-ref)]

                  (when (seq changed)
                    (sys-bus/publish! sys-bus ::m/asset-update {:updates changed})))
                (Thread/sleep 500)))

            file-watch-thread
            (doto (Thread. file-watch-fn "dev-http-file-watch")
              (.setDaemon true)
              (.start))]

        (swap! http-info-ref merge {:port http-port})

        (when https-port
          (>!! out {:type :println
                    :msg (format "shadow-cljs - HTTP server available at %s" https-url)}))

        (when http-port
          (>!! out {:type :println
                    :msg (format "shadow-cljs - HTTP server available at %s" http-url)}))

        (log/debug ::http-serve (dissoc server :instance))

        {:http-url http-url
         :https-url https-url
         :config config
         :instance server
         :file-watch-thread file-watch-thread
         :file-watch-ref file-watch-ref
         })

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

(defn get-server-configs []
  (-> (config/load-cljs-edn!)
      (transform-server-configs)))

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

(defn start-servers [sys-bus config ssl-context out]
  (let [configs
        (get-server-configs)

        servers
        (into [] (map #(start-build-server sys-bus config ssl-context out %)) configs)]

    {:servers servers
     :configs configs}
    ))

(defn stop-servers [{:keys [servers] :as state}]
  (doseq [{:keys [instance file-watch-ref] :as srv} servers]
    (when instance
      (undertow/stop instance))
    (when file-watch-ref
      (reset! file-watch-ref false)))
  (dissoc state :server :configs))

(defn start [sys-bus config ssl-context out]
  (let [sub-chan
        (-> (async/sliding-buffer 1)
            (async/chan))

        sub
        (sys-bus/sub sys-bus ::m/config-watch sub-chan true)

        state-ref
        (-> (start-servers sys-bus config ssl-context out)
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
                            :msg "shadow-cljs - HTTP config change, restarting servers"})
                  (swap! state-ref stop-servers)
                  (swap! state-ref merge (start-servers sys-bus new-config ssl-context out))))
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