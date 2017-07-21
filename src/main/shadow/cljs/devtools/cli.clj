(ns shadow.cljs.devtools.cli
  (:gen-class)
  (:require [shadow.runtime.services :as rt]
            [clojure.tools.cli :as cli]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.cli-opts :as opts]
            [clojure.repl :as repl]
            [shadow.cljs.devtools.server.worker.ws :as ws]
            [shadow.cljs.devtools.server.web.common :as web-common]
            [aleph.http :as aleph]
            [aleph.netty :as netty]
            [shadow.cljs.devtools.server.util :as util]
            [shadow.cljs.devtools.server.common :as common]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.api :as api]
            [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.server.runtime :as runtime]
            [shadow.cljs.devtools.errors :as errors]
            [shadow.http.router :as http]
            [shadow.cljs.build :as cljs]
            [shadow.cljs.node :as node]))

;; use namespaced keywords for every CLI specific option
;; since all options are passed to the api/* and should not conflict there

(def default-npm-config
  {:id :npm
   :target :npm-module
   :runtime :node
   :output-dir "node_modules/shadow-cljs"})

(defn web-root
  "only does /worker requests"
  [req]
  (prn [:path-tokens (::http/path-tokens req)])
  (http/route req
    (:ANY "^/worker" ws/process)
    web-common/not-found))

(defn get-ring-handler [config app-promise]
  (fn [ring-map]
    (let [app (deref app-promise 1000 ::timeout)]
      (if (= app ::timeout)
        {:status 501
         :body "App not ready!"}
        (-> app
            (assoc :ring-request ring-map)
            (http/prepare)
            (web-root))))))

(defn start []
  (let [{:keys [cache-root] :as config}
        (config/load-cljs-edn)

        app-promise
        (promise)

        ring
        (get-ring-handler config app-promise)

        host
        (get-in config [:http :host] "localhost")

        ;; this uses a random port so we can start multiple instances
        ;; eventually this will all be done in :server mode
        http
        (aleph/start-server ring {:host host
                                  :port 0})

        http-port
        (netty/port http)

        app
        (-> {::started (System/currentTimeMillis)
             :config config
             :out (util/stdout-dump (:verbose config))
             :http {:port (netty/port http)
                    :host host
                    :server http}}
            (rt/init (common/app config))
            (rt/start-all))]

    (deliver app-promise app)

    app
    ))

(defn stop [{:keys [http] :as app}]
  (rt/stop-all app)
  (let [netty (:server http)]
    (.close netty)
    (netty/wait-for-close netty)))

(defn with-app
  [thunk]
  (if @runtime/instance-ref
    (thunk)
    (let [app (start)]
      (runtime/set-instance! app)
      (try
        (thunk)
        (finally
          (runtime/reset-instance!)
          (stop app)
          )))))

(defn do-build-command [{:keys [action options] :as opts} build-config]
  (case action
    :release
    (api/release* build-config options)

    :check
    (api/check* build-config options)

    :compile
    (api/compile* build-config options)))

(defn do-build-commands
  [{:keys [action options] :as opts} build-ids]
  (let [{:keys [builds] :as config}
        (config/load-cljs-edn!)]

    (->> build-ids
         (map (fn [build-id]
                (or (get builds build-id)
                    (do (println (str "No config for build \"" (name build-id) "\" found."))
                        ;; FIXME: don't repeat this
                        (println (str "Available builds are: " (->> builds
                                                                    (keys)
                                                                    (map name)
                                                                    (str/join ", "))))))))
         (remove nil?)
         (map #(future (do-build-command opts %)))
         (into []) ;; force lazy seq
         (map deref)
         (into []) ;; force again
         )))

(defn main [& args]
  (let [{:keys [action builds options summary errors] :as opts}
        (opts/parse args)]

    (cond
      (:version options)
      (println "TBD")

      (or (:help options) (seq errors))
      (opts/help opts)

      (= action :test)
      (api/test-all)

      (contains? #{:compile :check :release} action)
      (do-build-commands opts builds)

      (contains? #{:watch :node-repl :cljs-repl :clj-repl :server} action)
      (server/from-cli action builds options))))

(defn print-main-error [e]
  (try
    (errors/user-friendly-error e)
    (catch Exception e2
      (println "failed to format error because of:")
      (repl/pst e2)
      (flush)
      (println "actual error:")
      (repl/pst e)
      (flush)
      )))

(defn print-token
  "attempts to print the given token to stdout which may be a socket
   if the client already closed the socket that would cause a SocketException
   so we ignore any errors since the client is already gone"
  [token]
  (try
    (println token)
    (catch Exception e
      ;; CTRL+D closes socket so we can't write to it anymore
      )))

(defn from-remote
  "the CLI script calls this with 2 extra token (uuids) that will be printed to notify
   the script whether or not to exit with an error code, it also causes the client to
   disconnect instead of us forcibly dropping the connection"
  [complete-token error-token args]
  (try
    (apply main args)
    (print-token complete-token)
    (catch Exception e
      (print-main-error e)
      (println error-token))))

;; direct launches don't need to mess with tokens
(defn -main [& args]
  (try
    (apply main args)
    (catch Exception e
      (print-main-error e)
      (System/exit 1))))

(defn autotest
  "no way to interrupt this, don't run this in nREPL"
  []
  (-> (api/test-setup)
      (cljs/watch-and-repeat!
        (fn [state modified]
          (-> state
              (cond->
                ;; first pass, run all tests
                (empty? modified)
                (node/execute-all-tests!)
                ;; only execute tests that might have been affected by the modified files
                (not (empty? modified))
                (node/execute-affected-tests! modified))
              )))))

(defn test-all []
  (api/test-all))

(comment
  (defn test-affected [test-ns]
    (api/test-affected [(cljs/ns->cljs-file test-ns)])))