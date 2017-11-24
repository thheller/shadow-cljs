(ns shadow.cljs.devtools.server
  (:require [aleph.http :as aleph]
            [clojure.core.async :as async :refer (thread go <!)]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [aleph.netty :as netty]
            [ring.middleware.file :as ring-file]
            [ring.middleware.params :as ring-params]
            [shadow.repl :as r]
            [shadow.http.router :as http]
            [shadow.runtime.services :as rt]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.server.web :as web]
            [shadow.cljs.devtools.server.config-watch :as config-watch]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.common :as common]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.socket-repl :as socket-repl]
            [shadow.cljs.devtools.server.runtime :as runtime]
            [shadow.cljs.devtools.server.nrepl :as nrepl]
            [shadow.cljs.devtools.server.dev-http :as dev-http]
            [shadow.cljs.devtools.server.ring-gzip :as ring-gzip]
            [ring.middleware.resource :as ring-resource])
  (:import (io.netty.handler.ssl SslContextBuilder)
           (javax.net.ssl KeyManagerFactory)
           (java.security KeyStore)
           (java.io FileInputStream)
           (java.net BindException)))

(def app-config
  (merge
    common/app-config
    {:dev-http
     {:depends-on [:ssl-context :executor :out]
      :start dev-http/start
      :stop dev-http/stop}
     :out
     {:depends-on [:config]
      :start (fn [config]
               (util/stdout-dump (:verbose config)))
      :stop async/close!}}))

(defn get-ring-handler
  [{:keys [dev-mode cache-root] :as config} app-promise]
  (let [ui-root
        (io/file cache-root "ui")]

    (-> (fn [ring-map]
          (let [app (deref app-promise 1000 ::timeout)]
            (if (= app ::timeout)
              {:status 501
               :body "App not ready!"}
              (-> app
                  (assoc :ring-request ring-map)
                  (http/prepare)
                  (web/root)))))
        (cond->
          (.exists ui-root)
          (ring-file/wrap-file ui-root)

          (not (.exists ui-root))
          (ring-resource/wrap-resource "shadow/cljs/ui/dist"))
        ;; (reload/wrap-reload {:dirs ["src/main"]})
        (ring-params/wrap-params)
        (ring-gzip/wrap-gzip)
        )))

(defmacro do-shutdown [& body]
  `(try
     ~@body
     (catch Throwable t#
       (println t# ~(str "shutdown failed: " (pr-str body))))))

(defn shutdown-system [{:keys [http port-files socket-repl cli-repl nrepl] :as app}]
  (println "shutting down ...")
  (do-shutdown
    (doseq [port-file (vals port-files)]
      (.delete port-file)))
  (do-shutdown (rt/stop-all app))
  (do-shutdown (socket-repl/stop socket-repl))
  (do-shutdown (socket-repl/stop cli-repl))
  (when nrepl
    (do-shutdown (nrepl/stop nrepl)))
  (let [netty (:server http)]
    (do-shutdown
      (.close netty)
      (netty/wait-for-close netty)))

  (println "shutdown complete."))

(defn make-port-files [cache-root ports]
  (io/make-parents (io/file cache-root "foo.txt"))

  (reduce-kv
    (fn [result key port]
      (assert (keyword? key))
      (assert (pos-int? port))
      (let [port-file
            (doto (io/file cache-root (str (name key) ".port"))
              (.deleteOnExit))]
        (spit port-file (str port))
        (assoc result key port-file)))
    {}
    ports))

(defn setup-ssl [http-config ssl]
  (let [key-manager
        (KeyManagerFactory/getInstance
          (KeyManagerFactory/getDefaultAlgorithm))

        key-store
        (KeyStore/getInstance
          (KeyStore/getDefaultType))

        pw
        (.toCharArray (get ssl :password "shadow-cljs"))]

    (with-open [fs (FileInputStream. (get ssl :keystore "ssl/keystore.jks"))]
      (.load key-store fs pw))

    (.init key-manager key-store pw)

    (let [ssl-context
          (-> (SslContextBuilder/forServer key-manager)
              (.build))]

      (assoc http-config
        :ssl true
        :ssl-context ssl-context)
      )))

(defn start-http [ring {:keys [port strict] :as http-config}]
  (loop [port (or port 9630)]
    (let [srv
          (try
            (aleph/start-server ring (assoc http-config :port port))
            (catch BindException e
              (log/warnf "TCP Port %d in use." port)
              (when strict
                (throw e))))]

      (or srv (recur (inc port)))
      )))

(defn start-system
  [app {:keys [cache-root] :as config}]
  (let [app-promise
        (promise)

        ring
        (get-ring-handler config app-promise)

        {:keys [http ssl]}
        config

        {:keys [ssl-context] :as http-config}
        (-> {:host "localhost"}
            (merge http)
            (cond->
              ssl
              (setup-ssl ssl)))

        http
        (start-http ring http-config)

        http-port
        (netty/port http)

        socket-repl-config
        (:socket-repl config)

        socket-repl
        (socket-repl/start socket-repl-config app-promise)

        cli-repl-config
        {:port 0 ;; random port, not for humans
         :prompt false
         :print false}

        ;; remote entry point for the CLI tool that just sends one command
        ;; and waits for the socket to close, not using the normal socket REPL
        ;; because that prints/prompts for humans
        cli-repl
        (socket-repl/start cli-repl-config app-promise)

        nrepl
        (nrepl/start (:nrepl config))

        ;; FIXME: should refuse to start if a pid already exists
        port-files
        (make-port-files cache-root
          {:nrepl (:port nrepl)
           :socket-repl (:port socket-repl)
           :cli-repl (:port cli-repl)
           :http http-port})

        app
        (-> {::started (System/currentTimeMillis)
             :config config
             :ssl-context ssl-context
             :http {:port http-port
                    :host (:host http-config)
                    :ssl (:ssl http-config)
                    :server http}
             :port-files port-files
             :nrepl nrepl
             :socket-repl socket-repl
             :cli-repl cli-repl}
            (rt/init app)
            (rt/start-all))]

    (deliver app-promise app)

    ;; autostart is not cool?
    #_(future
        ;; OCD because I want to print the shadow-cljs info of start!
        ;; before any build output
        (Thread/sleep 100)
        (when-let [{:keys [autostart] :as srv-config} (:server config)]
          (doseq [build-id autostart]
            (super/start-worker (:supervisor app) build-id)
            )))

    app
    ))

(defn load-config []
  (-> (config/load-cljs-edn)
      ;; system config doesn't need build infos
      (dissoc :builds)))

(defn start!
  ([]
   (let [config (load-config)]
     (start! config app-config)))
  ([sys-config]
   (start! sys-config app-config))
  ([sys-config app]
   (if (runtime/get-instance)
     ::already-running
     (let [{:keys [http ssl-context socket-repl nrepl config] :as app}
           (start-system app sys-config)]

       (println (str "shadow-cljs - server running at http" (when ssl-context "s") "://" (:host http) ":" (:port http)))

       (when (get-in config [:socket-repl :port])
         (println (str "shadow-cljs - socket repl running at " (:host socket-repl) ":" (:port socket-repl))))

       (when (get-in config [:nrepl :port])
         (println (str "shadow-cljs - nrepl running at "
                       (-> (:server-socket nrepl) (.getInetAddress))
                       ":" (:port nrepl))))

       (runtime/set-instance! app)
       ::started
       ))))

(defn stop! []
  (when-let [inst @runtime/instance-ref]
    (shutdown-system inst)
    (runtime/reset-instance!)
    ))

(defn -main [& args]
  (start!)
  (-> @runtime/instance-ref
      (get-in [:http :server])
      (netty/wait-for-close))
  (shutdown-agents))

(defn wait-for-http []
  (-> @runtime/instance-ref
      (get-in [:http :server])
      (netty/wait-for-close)))

(defn wait-for-eof! []
  (loop []
    (let [x (try
              (read *in* false ::eof)
              (catch Exception e
                ::eof))]
      (when (not= x ::eof)
        (recur)))))

(defn watch-builds [config build-configs options]
  (let [{:keys [supervisor] :as app}
        @runtime/instance-ref

        out-chan
        (-> (async/sliding-buffer 100)
            (async/chan))

        verbose
        (or (:verbose options)
            (:verbose config))

        {:keys [supervisor] :as app}
        @runtime/instance-ref]

    (doseq [{:keys [build-id] :as build-config} build-configs]
      (println "shadow-cljs - watching build" build-id)
      (-> (api/get-or-start-worker build-config options)
          (worker/watch out-chan false)
          (worker/start-autobuild)))

    (go (loop []
          (when-some [msg (<! out-chan)]
            (try
              (util/print-worker-out msg verbose)
              (catch Exception e
                (prn [:print-worker-out-error e])))
            (recur)
            )))
    ))

(defn from-cli [action builds {:keys [verbose] :as options}]
  (let [config
        (config/load-cljs-edn!)

        build-configs
        (->> builds
             (map (fn [build-id]
                    (let [build-config (get-in config [:builds build-id])]
                      (when-not build-config
                        (println (str "No config for build \"" (name build-id) "\" found.")))

                      build-config
                      )))
             (remove nil?)
             (into []))

        already-running?
        (some? @runtime/instance-ref)]

    (if (and (contains? #{:watch :cljs-repl} action)
             (empty? build-configs))
      (println "Build id required.")

      (do (when-not already-running?
            (start!))

          (case action
            :watch
            (let [before (api/active-builds)]
              (watch-builds config build-configs options)

              (wait-for-eof!)

              ;; stop only builds that weren't running before
              (doseq [build builds
                      :when (not (contains? before build))]
                (api/stop-worker build)))

            :clj-repl
            (r/enter-root {}
              (socket-repl/repl {}))

            :cljs-repl
            (let [{:keys [supervisor] :as app}
                  @runtime/instance-ref

                  {:keys [build-id] :as build-config}
                  (first build-configs)]

              (if (false? (get-in build-config [:devtools :enabled]))
                (println (format "Build %s has :devtools {:enabled false}, can't connect to a REPL" build-id))
                (let [worker
                      (super/get-worker supervisor build-id)

                      started-by-us?
                      (nil? worker)

                      worker
                      (-> (or worker
                              (-> (super/start-worker supervisor build-config)
                                  (worker/compile)))
                          ;; need to sync in case it is still compiling
                          (worker/sync!))]

                  (api/repl build-id)

                  (when started-by-us?
                    (super/stop-worker supervisor build-id)))))

            :node-repl
            (let [{:keys [supervisor] :as app} @runtime/instance-ref

                  worker
                  (super/get-worker supervisor :node-repl)]
              ;; FIXME: connect to if already running or launch another one?
              (if-not worker
                (do (println "shadow-cljs - starting node-repl")
                    (api/node-repl options))

                (do (println "shadow-cljs - connecting to running node-repl")
                    (api/repl :node-repl))
                ))

            ;; makes this a noop if server is already running
            :server
            (if already-running?
              (println "server already running")
              (wait-for-eof!)))

          (when-not already-running?
            (stop!))))))

(comment
  (start!)

  (stop!))
