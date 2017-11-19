(ns shadow.cljs.devtools.server.dev-http
  "provides a basic static http server per build"
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async :refer (>!!)]
            [shadow.cljs.devtools.server.web.common :as common]
            [ring.middleware.resource :as ring-resource]
            [ring.middleware.file :as ring-file]
            [ring.middleware.file-info :as ring-file-info]
            [ring.middleware.content-type :as ring-content-type]
            [aleph.http :as aleph]
            [aleph.netty :as netty]
            [clojure.tools.logging :as log]
            [shadow.cljs.devtools.config :as config]
            [clojure.string :as str]
            [shadow.cljs.devtools.server.ring-gzip :as ring-gzip])
  (:import (java.net BindException)))

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
  [ssl-context
   executor
   out
   {:keys [build-id http-root http-port http-handler]
    :or {http-port 0}
    :as config}]

  (let [root-dir
        (io/file http-root)

        default-handler
        (if-not http-handler
          common/not-found

          (let [http-handler-ns
                (require (symbol (namespace http-handler)))
                http-handler-fn
                @(find-var http-handler)]

            (fn [req]
              (-> req
                  (assoc :http-root root-dir
                    :build-id build-id
                    :devtools config)
                  (http-handler-fn)))))]

    (when-not (.exists root-dir)
      (io/make-parents (io/file root-dir "index.html")))

    (try
      (let [http-handler
            (-> default-handler
                ;; some default resources, only used if no file exists
                ;; currently only contains the CLJS logo as favicon.ico
                ;; pretty much only doing this because of the annoying
                ;; 404 chrome devtools show then no icon exists
                (ring-resource/wrap-resource "shadow/cljs/devtools/server/dev_http")
                (ring-content-type/wrap-content-type)
                (ring-file/wrap-file root-dir {:allow-symlinks? true
                                               :index-files? true})
                (ring-file-info/wrap-file-info
                  ;; source maps
                  {"map" "application/json"})

                (ring-gzip/wrap-gzip)
                (disable-all-kinds-of-caching))

            aleph-options
            (-> {:port http-port
                 :executor executor
                 :shutdown-executor? false}
                (cond->
                  ssl-context
                  (assoc :ssl-context ssl-context)))

            instance
            (aleph/start-server http-handler aleph-options)

            port
            (netty/port instance)]

        (>!! out {:type :println
                  :msg (format "shadow-cljs - HTTP server for \"%s\" available at http%s://%s:%s" build-id (if ssl-context "s" "") "localhost" http-port)})
        (log/debug ::http-serve {:http-port port :http-root http-root :build-id build-id})

        {:build-id build-id
         :port port
         :instance instance
         :ssl (boolean ssl-context)})
      (catch BindException e
        (>!! out {:type :println
                  :msg (format "[WARNING] shadow-cljs - HTTP server at port %s failed, port is in use." http-port)})
        nil)
      (catch Exception e
        (log/warn ::start-error http-root http-port e)
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

;; FIXME: use config watch to restart servers on config change
(defn start [ssl-context executor out]
  (let [configs
        (get-server-configs)

        servers
        (into [] (map #(start-build-server ssl-context executor out %)) configs)]

    {:servers servers
     :configs configs}
    ))

(defn stop [{:keys [servers] :as svc}]
  (doseq [{:keys [instance] :as srv} servers
          :when instance]
    (.close instance)))