(ns shadow.devtools.server.system
  (:import [java.io InputStream ByteArrayOutputStream]
           [java.net BindException])
  (:require [aleph.http :as aleph]
            [aleph.netty :as netty]
            [shadow.server.runtime :as rt]
            [shadow.server.assets :as assets]
            [shadow.devtools.server.web :as web]
            [shadow.devtools.server.services.fs-watch :as fs-watch]
            [shadow.devtools.server.services.build :as build]
            [shadow.devtools.server.services.explorer :as explorer]
            [clojure.core.async :as async :refer (thread)]
            [clojure.java.io :as io]
            [clojure.java.jmx :as jmx]
            [clojure.edn :as edn]
            [ring.middleware.reload :as reload]
            [ring.middleware.file :as file]
            [shadow.cljs.build :as cljs]
            [shadow.devtools.server.services.config :as config]
            [cognitect.transit :as transit]
            [manifold.stream :as s]))

(defonce runtime nil)

(def default-config
  {:http
   {:port 8200
    :host "localhost"}

   :assets
   {:package-name "devtools"
    :css-root "/assets/devtools/css"
    :css-manifest "public/assets/devtools/css/manifest.json"
    :js-root "/assets/devtools/js"
    :js-manifest "public/assets/devtools/js/manifest.json"}

   :client-assets
   {:package-name "client"
    :css-root "/assets/client/css"
    :css-manifest "public/assets/client/css/manifest.json"
    :js-root "/assets/client/js"
    :js-manifest "public/assets/client/js/manifest.json"}
   })

(defn make-app []
  {:edn-reader
   {:depends-on []
    :start
    (fn []
      (fn [input]
        (cond
          (instance? String input)
          (edn/read-string input)
          (instance? InputStream input)
          (edn/read input)
          :else
          (throw (ex-info "dunno how to read" {:input input})))))
    :stop (fn [reader])}

   :transit-str
   {:depends-on []
    :start
    (fn []
      (fn [data]
        (let [out (ByteArrayOutputStream. 4096)
              w (transit/writer out :json)]
          (transit/write w data)
          (.toString out)
          )))

    :stop (fn [x])}

   :assets
   {:depends-on [:config]
    :start (fn [{:keys [assets] :as config}]
             (assets/start assets))
    :stop assets/stop}

   :client-assets
   {:depends-on [:config]
    :start (fn [{:keys [client-assets] :as config}]
             (assets/start client-assets))
    :stop assets/stop}

   :fs-watch
   {:depends-on []
    :start fs-watch/start
    :stop fs-watch/stop}

   :build-config
   {:depends-on []
    :start config/start
    :stop config/stop}

   :build
   {:depends-on [:fs-watch]
    :start build/start
    :stop build/stop}

   :explorer
   {:depends-on [:fs-watch]
    :start explorer/start
    :stop explorer/stop}

   })

(defn start-app [app]
  (rt/start-all app (make-app)))

(defn stop-app [app]
  (rt/stop-all app (make-app)))

(defn restart-app [runtime]
  (let [{:keys [app system]}
        @runtime

        next-app
        (start-app system)]

    (vswap! runtime assoc
      ::restart (System/currentTimeMillis)
      :app next-app)

    ;; stop only when starting succeeded
    (stop-app app)

    ::restarted
    ))

(defn get-ring-handler
  [config system-ref]
  (-> (fn [ring-map]
        (let [app
              (-> (:app @system-ref)
                  (assoc :ring-request ring-map))]
          (web/root app)))
      (file/wrap-file (io/file "public"))
      ;; (reload/wrap-reload {:dirs ["src/main"]})
      ))

(defmacro do-shutdown [& body]
  `(try
     ~@body
     (catch Throwable t#
       (println t# ~(str "shutdown failed: " (pr-str body))))))

(defn shutdown-aleph []
  (when (realized? netty/epoll-client-group)
    (.shutdown @netty/epoll-client-group))
  (when (realized? netty/nio-client-group)
    (.shutdown @netty/nio-client-group))

  (.shutdown aleph/default-connection-pool))

(defn shutdown-system [{:keys [app http] :as system}]
  (println "SYSTEM SHUTDOWN")
  (prn [:closing-http])
  (do-shutdown (.close http))
  (prn [:closing-aleph])
  (do-shutdown (shutdown-aleph))
  (prn [:closing-app])
  (do-shutdown (stop-app app))
  (prn [:shutdown-complete]))

(defn shutdown-thread [runtime]
  (let [file (io/file "tmp/shutdown.txt")]
    (when-not (.exists file)
      (io/make-parents file))

    (spit file (jmx/read "java.lang:type=Runtime" :Name))

    (let [start-mod (.lastModified file)]
      (thread
        (loop []
          (if (> (.lastModified file) start-mod)
            (do (shutdown-system @runtime)
                (shutdown-agents))
            ;; not modified
            (do (Thread/sleep 500)
                (recur))))))))

(defn touch-shutdown []
  (doto (io/file "tmp/shutdown.txt")
    (.setLastModified (System/currentTimeMillis))))

(defn start-http [ring config]
  (loop []
    (if-let [http
             (try
               (aleph/start-server ring config)
               (catch BindException e
                 (prn "SOCKET IN USE!")
                 (touch-shutdown)
                 (Thread/sleep 250)
                 nil))]
      http
      (recur)
      )))

(defn start-system
  ([]
   (start-system default-config))
  ([config]
   (let [runtime-ref
         (volatile! {})

         system
         {::started (System/currentTimeMillis)
          :config config}

         app
         (start-app system)]

     (vreset! runtime-ref
       {:system system
        :app app})

     (let [ring
           (get-ring-handler config runtime-ref)

           hk
           (start-http ring (:http config))]

       (vswap! runtime-ref assoc :http hk))

     runtime-ref
     )))

(defn start-cli []
  (start-system))

(defn -main []
  (println "Starting DEVTOOLS")
  (let [runtime-ref (start-system default-config)]
    (println "DEVTOOLS ready")
    (shutdown-thread runtime-ref)
    (alter-var-root #'runtime (fn [_] runtime-ref))
    nil
    ))

