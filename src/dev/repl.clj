(ns repl
  (:require [shadow.devtools.server.system :as sys]
            [clojure.pprint :refer (pprint)]
            [clojure.spec.test :as st]
            [shadow.devtools.server.services.build :as build]
            [shadow.devtools.server.config :as config]
            [shadow.devtools.cli :as cli]
            ))

(defonce inst (volatile! nil))

(defn app []
  (:app @inst))

(defn start []
  (st/instrument)

  (let [runtime-ref (sys/start-system)]
    (vreset! inst runtime-ref)

    (let [{:keys [build] :as app}
          (:app @runtime-ref)

          [script lib & others]
          (config/load-cljs-edn!)]

      (-> (build/proc-start build)
          (build/configure script)
          (build/start-autobuild)
          (build/watch (cli/stdout-dump)))

      #_(-> (build/proc-start build)
            (build/configure
              :dev
              {:id :client
               :target :browser
               :modules
               {:main
                {:entries ['shadow.devtools.browser.app]
                 :depends-on #{}}}
               :public-dir "public/assets/client/js"
               :public-path "/assets/client/js"
               :devtools
               {:console-support true
                :before-load 'shadow.devtools.browser.app/stop
                :after-load 'shadow.devtools.browser.app/start}})
            (build/start-autobuild)
            (build/watch (log-dump "CLIENT")))
      ))

  :started)

(defn stop []
  (st/unstrument)

  (when-let [sys @inst]
    (sys/shutdown-system @sys)
    (vreset! inst nil))

  :stopped)

#_(ns-tools/set-refresh-dirs "src/main")

(defn go []
  (stop)
  ;; this somehow breaks reloading
  ;; the usual :reloading message tells me that is namespace is being reloaded
  ;; but when the new instance is launched it is still using the old one
  ;; i cannot figure out why
  ;; (ns-tools/refresh :after 'repl/start)
  (start))