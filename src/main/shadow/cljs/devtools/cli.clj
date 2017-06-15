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
            [shadow.http.router :as http]))

;; use namespaced keywords for every CLI specific option
;; since all options are passed to the api/* and should not conflict there

(def default-opts
  {:autobuild true})

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

(defn main [& args]
  (try
    (let [{:keys [options summary errors] :as opts}
          (opts/parse args)

          options
          (merge default-opts options)]

      (cond
        (or (::opts/help options) (seq errors))
        (opts/help opts)

        (= :server (::opts/mode options))
        (server/from-cli options)

        :else
        (let [{::opts/keys [build npm]} options

              build-config
              (cond
                (keyword? build)
                (config/get-build! build)

                npm
                (merge default-npm-config (config/get-build :npm))

                :else
                nil)]

          (if-not (some? build-config)
            (do (println "Please specify a build or use --npm")
                (opts/help opts))

            (case (::opts/mode options)
              :release
              (api/release* build-config options)

              :check
              (api/check* build-config options)

              :dev
              (with-app #(api/dev* build-config options))

              ;; make :once the default
              (api/once* build-config options)
              )))))

    (catch Exception e
      (try
        (errors/user-friendly-error e)
        (catch Exception e2
          (println "failed to format error because of:")
          (repl/pst e2)
          (flush)
          (println "actual error:")
          (repl/pst e)
          (flush)
          )))))

(defn from-remote [args]
  (apply main args))

(defn -main [& args]
  (apply main args))

(comment
  ;; FIXME: fix these properly and create CLI args for them
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

  (defn test-affected [test-ns]
    (api/test-affected [(cljs/ns->cljs-file test-ns)])))