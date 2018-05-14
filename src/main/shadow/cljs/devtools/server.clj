(ns shadow.cljs.devtools.server
  (:require [clojure.core.async :as async :refer (thread go <!)]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
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
            [shadow.undertow :as undertow]
            [ring.middleware.resource :as ring-resource]
            [clojure.string :as str])
  (:import (java.net BindException SocketException NetworkInterface Inet4Address URL Socket)
           [java.lang.management ManagementFactory]))

(def app-config
  (merge
    common/app-config
    {:dev-http
     {:depends-on [:system-bus :config :ssl-context :out]
      :start dev-http/start
      :stop dev-http/stop}
     :out
     {:depends-on [:config]
      :start (fn [config]
               (util/stdout-dump (:verbose config)))
      :stop async/close!}}))

(defn wait-for [app-ref]
  (or @app-ref
      (loop [x 0]
        (Thread/sleep 100)
        (or @app-ref
            (if (> x 10)
              ::timeout
              (recur (inc x)))))))

(defn get-ring-handler [app-ref]
  (fn [ring-map]
    (let [app (wait-for app-ref)]
      (if (= app ::timeout)
        {:status 501
         :body "App not ready!"}
        (-> app
            (assoc :ring-request ring-map)
            (http/prepare)
            (web/root))))))

(defn get-ring-middleware
  [{:keys [dev-mode cache-root] :as config} handler]
  (let [ui-root
        (io/file cache-root "ui")]

    (-> handler
        (ring-resource/wrap-resource "shadow/cljs/ui/dist")
        (cond->
          (.exists ui-root)
          (ring-file/wrap-file ui-root))

        (ring-resource/wrap-resource "shadow/cljs/devtools/server/web/resources")
        ;; (reload/wrap-reload {:dirs ["src/main"]})
        (ring-params/wrap-params)
        (ring-gzip/wrap-gzip)
        )))

;; println may fail if the socket already disconnected
;; just discard the print if it fails
(defn discard-println [msg]
  (try
    (println msg)
    (catch Exception ignored)))

(defmacro do-shutdown [& body]
  `(try
     ~@body
     (catch Throwable t#
       (log/warn t# "shutdown failed" ~(pr-str body))
       (discard-println ~(str "shutdown failed: " (pr-str body))))))

(defn shutdown-system [{:keys [shutdown-hook http port-files-ref socket-repl cli-repl nrepl] :as app}]
  (discard-println "shutting down ...")
  (try
    (. (Runtime/getRuntime) (removeShutdownHook shutdown-hook))
    (catch IllegalStateException e
      ;; can't remove the hook while running the hook
      ))

  (do-shutdown
    (doseq [port-file (vals @port-files-ref)]
      (.delete port-file)))

  (when socket-repl
    (do-shutdown (socket-repl/stop socket-repl)))

  (do-shutdown (socket-repl/stop cli-repl))

  (when nrepl
    (do-shutdown (nrepl/stop nrepl)))

  (do-shutdown (undertow/stop (:server http)))

  (do-shutdown (rt/stop-all app))

  (discard-println "shutdown complete."))

(defn make-port-files [cache-root ports]
  (io/make-parents (io/file cache-root "foo.txt"))

  (reduce-kv
    (fn [result key port]
      (assert (keyword? key))
      (if-not (pos-int? port)
        result
        (let [port-file
              (doto (io/file cache-root (str (name key) ".port"))
                (.deleteOnExit))]
          (spit port-file (str port))
          (assoc result key port-file))))
    {}
    ports))

(defn start-http [config {:keys [port strict] :as http-config} ring]
  (let [middleware-fn #(get-ring-middleware config %)]
    (loop [port (or port 9630)]
      (let [srv
            (try
              (undertow/start (assoc http-config :port port) ring middleware-fn)
              (catch Exception e
                (cond
                  strict
                  (throw e)

                  (instance? BindException (.getCause e))
                  (log/warnf "TCP Port %d in use." port)

                  :else
                  (log/warnf e "HTTP startup failed")
                  )))]

        (or srv (recur (inc port)))
        ))))

(declare stop!)

(defn find-local-addrs []
  (for [ni (enumeration-seq (NetworkInterface/getNetworkInterfaces))
        :when (not (.isLoopback ni))
        :when (.isUp ni)
        :let [display-name (.getDisplayName ni)]
        :when
        (and (not (str/includes? display-name "VirtualBox"))
             (not (str/includes? display-name "utun")))
        addr (enumeration-seq (.getInetAddresses ni))
        ;; probably don't need ipv6 for dev
        :when (instance? Inet4Address addr)]
    [ni addr]))

;; (prn (find-local-addrs))

(defn select-server-addr []
  (let [addrs (find-local-addrs)]
    (when (seq addrs)
      (let [[ni addr] (first addrs)]

        (println (format "shadow-cljs - Using IP \"%s\" from Interface \"%s\"" (.getHostAddress addr) (.getDisplayName ni)))

        (when (not= 1 (count addrs))
          (log/infof "Found multiple IPs, might be using the wrong one. Please report all interfaces should the chosen one be incorrect.")
          (doseq [[ni addr] addrs]
            (log/infof "Found IP:%s Interface:%s" (.getHostAddress addr) (.getDisplayName ni))))

        ;; would be neat if we could just use InetAddress/getLocalHost
        ;; but that returns my VirtualBox Adapter for some reason
        ;; which is incorrect and doesn't work
        (.getHostAddress addr)))))

(defn start-system
  [app-ref app-config {:keys [cache-root] :as config}]
  (when @app-ref
    (throw (ex-info "app-ref not nil" {:app-ref app-ref})))

  (let [{:keys [http ssl]}
        config

        server-addr
        (select-server-addr)

        pid
        (-> (ManagementFactory/getRuntimeMXBean)
            (.getName)
            (str/split #"@")
            (first)
            (Long/valueOf))

        ring
        (get-ring-handler app-ref)

        {:keys [ssl-context] :as http-config}
        (-> {:host "0.0.0.0"}
            (merge http)
            (cond->
              ssl
              (assoc :ssl-context (undertow/make-ssl-context ssl))))

        {:keys [http-port https-port] :as http}
        (start-http config http-config ring)

        socket-repl-config
        (:socket-repl config)

        socket-repl
        (try
          (socket-repl/start socket-repl-config app-ref)
          (catch Exception e
            (log/warn "failed to start socket-repl" (.getMessage e))
            nil
            ))

        cli-repl-config
        {:port 0 ;; random port, not for humans
         :prompt false
         :print false}

        ;; remote entry point for the CLI tool that just sends one command
        ;; and waits for the socket to close, not using the normal socket REPL
        ;; because that prints/prompts for humans
        cli-repl
        (socket-repl/start cli-repl-config app-ref)

        nrepl
        (try
          (nrepl/start (:nrepl config))
          (catch Exception e
            (log/warn "failed to start nrepl server" (.getMessage e))
            nil))

        port-files-ref
        (atom nil)

        shutdown-hook
        (Thread. stop!)

        app
        (-> {::started (System/currentTimeMillis)
             :server-pid pid
             :config config
             :shutdown-hook shutdown-hook
             :ssl-context ssl-context
             :http {:port (or https-port http-port)
                    :http-port http-port
                    :https-port https-port
                    :host (:host http-config)
                    :ssl (boolean https-port)
                    :server http}
             :port-files-ref port-files-ref
             :cli-repl cli-repl}
            (cond->
              server-addr
              (assoc-in [:http :addr] server-addr)
              socket-repl
              (assoc :socket-repl socket-repl)
              nrepl
              (assoc :nrepl nrepl))
            (rt/init app-config)
            (rt/start-all))

        pid-file
        (doto (io/file cache-root "server.pid")
          (.deleteOnExit))]

    (vreset! app-ref app)

    ;; do this as the very last setup to maybe fix circleci timing issue?
    (reset! port-files-ref
      (make-port-files cache-root
        (-> {:cli-repl (:port cli-repl)
             :http http-port
             :https-port https-port}
            (cond->
              nrepl
              (assoc :nrepl (:port nrepl))
              socket-repl
              (assoc :socket-repl (:port socket-repl)))
            )))

    (let [nrepl-port-file (io/file ".nrepl-port")]
      (when-not (.exists nrepl-port-file)
        (spit nrepl-port-file (str (:port nrepl)))
        (swap! port-files-ref assoc :nrepl-port nrepl-port-file)
        (.deleteOnExit nrepl-port-file)))

    (. (Runtime/getRuntime) (addShutdownHook shutdown-hook))

    (spit pid-file pid)

    app-ref))

(defn load-config []
  (-> (config/load-cljs-edn)
      ;; system config doesn't need build infos
      (dissoc :builds)))

(defn find-version-from-pom [pom-xml]
  (let [[_ version :as m]
        (->> (slurp pom-xml)
             (re-find #"<version>([^<]+)</version>"))]
    (when m
      version)))

(defn start!
  ([]
   (let [config (load-config)]
     (start! config app-config)))
  ([sys-config]
   (start! sys-config app-config))
  ([sys-config app]
   (if (runtime/get-instance)
     ::already-running
     (let [app-ref
           (start-system runtime/instance-ref app sys-config)

           {:keys [http ssl-context socket-repl nrepl config] :as app}
           @app-ref

           pom-xml
           (io/resource "META-INF/maven/thheller/shadow-cljs/pom.xml")

           version
           (if (nil? pom-xml)
             "<snapshot>"
             (find-version-from-pom pom-xml))]

       (println (str "shadow-cljs - server version: " version))
       (println (str "shadow-cljs - server running at http" (when ssl-context "s") "://" (:host http) ":" (:port http)))
       (println (str "shadow-cljs - socket repl running at " (:host socket-repl) ":" (:port socket-repl)))
       (println (str "shadow-cljs - nREPL server started on port " (:port nrepl)))

       ::started
       ))))

(defn stop! []
  (when-let [inst @runtime/instance-ref]
    (shutdown-system inst)
    (runtime/reset-instance!)
    ))

(defn reload! []
  (when-let [inst @runtime/instance-ref]
    (let [new-inst
          (-> inst
              (rt/stop-all)
              (rt/start-all))]

      (vreset! runtime/instance-ref new-inst)
      ::restarted
      )))

(defn remote-stop! []
  (stop!)
  (shutdown-agents))

(defn wait-for-stop! []
  (loop []
    (when (some? @runtime/instance-ref)
      (Thread/sleep 250)
      (recur))))

(defn -main [& args]
  (start!)
  (wait-for-stop!)
  (shutdown-agents))

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
      (api/watch* build-config options))

    (go (loop []
          (when-some [msg (<! out-chan)]
            (try
              (util/print-worker-out msg verbose)
              (catch Exception e
                (prn [:print-worker-out-error e])))
            (recur)
            )))
    ))

(defn stdin-closed? []
  (let [avail (.available System/in)]
    (if (= -1 avail)
      true
      (do (let [buf (byte-array avail)]
            (.read System/in buf))
          false))))

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
            (let [before (api/active-builds)

                  stop-builds!
                  (fn []
                    (doseq [build builds
                            :when (not (contains? before build))]
                      (api/stop-worker build)))]

              (watch-builds config build-configs options)

              ;; start threads to wait for exit condition
              ;; not doing this in the main thread since reading from a socket blocks and can't be interrupted properly
              ;; FIXME: write proper remote API, doing this on top of the REPL is annoying
              (if socket-repl/*socket*
                (future
                  ;; when remote socket is active read until eof as watch does not expect any other input
                  ;; but should not exit if something is entered accidentally
                  (loop []
                    (let [x (try
                              (read *in* false ::eof)
                              (catch Exception e
                                (log/warn e "read socket ex")
                                ::eof))]
                      (if (not= x ::eof)
                        (recur)
                        (stop-builds!)
                        ))))
                (future
                  ;; wait till stdin is closed
                  ;; stop all builds so the other loop can initiate the shutdown
                  ;; done in separate loop since we cannot reliably do a non-blocking read from a blocking socket
                  ;; sort of hacking that as it is when looking at System/in directly
                  (loop []
                    (when-not (stdin-closed?)
                      (Thread/sleep 100)
                      (recur)))

                  (stop-builds!)
                  ))

              ;; run until either the instance is removed
              ;; or all builds we started are stopped by other means
              (loop []
                (cond
                  (not (some? @runtime/instance-ref))
                  ::stopped

                  (not (some #(api/get-worker (:build-id %)) build-configs))
                  ::stopped

                  :else
                  (do (Thread/sleep 100)
                      (recur))))

              ;; if the builds were stopped by any other means the connected client should exit
              ;; since it is stuck in a blocking read thread above it we need to close the socket
              (when-let [s socket-repl/*socket*]
                (.close s)))

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
            (api/node-repl options)

            :browser-repl
            (api/browser-repl options)

            ;; makes this a noop if server is already running
            :server
            (if already-running?
              (println "server already running")
              (wait-for-stop!)))

          (when-not already-running?
            (stop!))))))

(comment
  (start!)

  (stop!))
