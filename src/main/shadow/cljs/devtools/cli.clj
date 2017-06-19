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

(defn do-build-command
  [{:keys [action options] :as opts} build-id]
  (let [{:keys [builds] :as config}
        (config/load-cljs-edn!)

        build-config
        (get builds build-id)

        ;; FIXME: what about automatic npm support mode?
        ;; I think it is better to always force a build config
        ;; since you are going to need one for release anyways
        #_(cond
            (keyword? build-id)
            (config/get-build! build)

            npm
            (merge default-npm-config (config/get-build :npm))

            :else
            nil)]

    (if-not (some? build-config)
      (do (println (str "No config for build \"" (name build-id) "\" found."))
          (println (str "Available builds are: " (->> builds
                                                      (keys)
                                                      (map name)
                                                      (str/join ", ")))))

      (case action
        :release
        (api/release* build-config options)

        :check
        (api/check* build-config options)

        :compile
        (api/once* build-config options)
        ))))

(defn main [& args]
  (try
    (let [{:keys [action builds options summary errors] :as opts}
          (opts/parse args)]

      (cond
        (:version options)
        (println "TBD")

        (or (:help options) (seq errors))
        (opts/help opts)

        (contains? #{:compile :check :release} action)
        (run! #(do-build-command opts %) builds)

        (contains? #{:watch :node-repl :cljs-repl :clj-repl :server} action)
        (server/from-cli action builds options)))

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

(defn from-remote [complete-token args]
  (apply main args)
  (try
    (println complete-token)
    (catch Exception e
      ;; CTRL+D closes socket so we can't write to it anymore
      )))

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