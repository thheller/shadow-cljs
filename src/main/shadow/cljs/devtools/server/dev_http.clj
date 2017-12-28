(ns shadow.cljs.devtools.server.dev-http
  "provides a basic static http server per build"
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async :refer (>!!)]
            [shadow.cljs.devtools.server.web.common :as common]
            [ring.middleware.resource :as ring-resource]
            [ring.middleware.file :as ring-file]
            [ring.middleware.file-info :as ring-file-info]
            [ring.middleware.content-type :as ring-content-type]
            [clojure.tools.logging :as log]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.server.ring-gzip :as ring-gzip]
            [shadow.undertow :as undertow])
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
  [config ssl-context out
   {:keys [build-id http-root http-port http-host http-resource-root http-handler]
    :or {http-port 0}
    :as config}]

  (let [root-dir
        (when (seq http-root)
          (io/file http-root))

        http-host
        (or (and (seq http-host) http-host)
            (get-in config [:http :host])
            "0.0.0.0")

        http-handler
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
            (undertow/start http-options http-handler middleware-fn)

            display-host
            (if (= "0.0.0.0" http-host) "localhost" http-host)]

        (when https-port
          (>!! out {:type :println
                    :msg (format "shadow-cljs - HTTP server for \"%s\" available at https://%s:%s" build-id display-host https-port)}))

        (when http-port
          (>!! out {:type :println
                    :msg (format "shadow-cljs - HTTP server for \"%s\" available at http://%s:%s" build-id display-host http-port)}))

        (log/debug ::http-serve (-> server
                                    (dissoc :instance)
                                    (assoc :build-id build-id)))

        {:build-id build-id
         :instance server})
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
(defn start [config ssl-context out]
  (let [configs
        (get-server-configs)

        servers
        (into [] (map #(start-build-server config ssl-context out %)) configs)]

    {:servers servers
     :configs configs}
    ))

(defn stop [{:keys [servers] :as svc}]
  (doseq [{:keys [instance] :as srv} servers
          :when instance]
    (undertow/stop instance)))