(ns repl
  (:require [shadow.devtools.server.livetest :as livetest]
            [shadow.devtools.server.system :as sys]
            [clojure.pprint :refer (pprint)]
            [clojure.spec.test :as st]
            [clojure.tools.namespace.repl :as ns-tools]
            [shadow.devtools.server.services.build :as build]
            [shadow.cljs.log :as cljs-log]
            [clojure.core.async :as async :refer (<!)]
            [clojure.tools.logging :as log]))

(defonce inst (volatile! nil))

(defn app []
  (:app @inst))

(defonce log-lock (Object.))

(defn log-dump [label]
  (let [chan
        (-> (async/sliding-buffer 10)
            (async/chan))]

    (async/go
      (loop []
        (when-some [x (<! chan)]
          (locking log-lock
            (println (str label ":")
              (case (:type x)
                :build-log
                (cljs-log/event->str (:event x))
                (pr-str x))))
          (recur)
          )))

    chan
    ))

(defn start []
  (st/instrument)

  (let [runtime-ref (sys/start-system)]
    (vreset! inst runtime-ref)

    (let [{:keys [build] :as app}
          (:app @runtime-ref)]
      (-> (build/proc-start build)
          (build/configure
            :dev
            {:id :self
             :target :browser
             :modules
             {:main
              {:entries ['shadow.devtools.frontend.app]
               :depends-on #{}}}
             :public-dir "public/assets/devtools/js"
             :public-path "/assets/devtools/js"
             :devtools
             {:console-support true
              :before-load 'shadow.devtools.frontend.app/stop
              :after-load 'shadow.devtools.frontend.app/start}})
          (build/start-autobuild)
          (build/watch (log-dump "SELF")))

      #_(-> (build/proc-start build)
            (build/configure
              :dev
              {:id :client
               :target :browser
               :modules
               {:main
                {:entries ['shadow.devtools.client.app]
                 :depends-on #{}}}
               :public-dir "public/assets/client/js"
               :public-path "/assets/client/js"
               :devtools
               {:console-support true
                :before-load 'shadow.devtools.client.app/stop
                :after-load 'shadow.devtools.client.app/start}})
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