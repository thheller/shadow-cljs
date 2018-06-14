(ns shadow.cljs.devtools.server.config-watch
  (:require [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.server.env :as env]
            [clojure.java.io :as io]
            [clojure.core.async :as async :refer (thread <!!)]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            [clojure.tools.logging :as log]))

(defn- service? [x]
  (and (map? x)
       (::service x)))

(defn get-last-modified []
  (let [file (io/file "shadow-cljs.edn")]
    (if-not (.exists file)
      0
      (.lastModified file))))

(defn watch-loop [stop-ref system-bus]
  (let [{:keys [dependencies] :as config}
        (config/load-cljs-edn)]

    (loop [ts (get-last-modified)
           config config]

      (if @stop-ref
        ::stopped
        (let [ts-test (get-last-modified)]
          (if (= ts-test ts)
            (do (Thread/sleep 1000)
                (recur ts config))
            (let [new-config (config/load-cljs-edn)]
              (log/debug "config-watch trigger")

              (when (not= new-config config)
                (log/debug "config-watch update global")
                (sys-bus/publish! system-bus ::sys-msg/config-watch {:config new-config}))

              (doseq [{:keys [build-id] :as new} (-> new-config :builds (vals))
                      :when (not= new (get-in config [:builds build-id]))]
                (log/debugf "config-watch update %s" build-id)
                (sys-bus/publish! system-bus [::sys-msg/config-watch build-id] {:config new}))

              (reset! env/dependencies-modified-ref (not= dependencies (:dependencies new-config)))

              (recur ts-test new-config)
              )))))))

(defn start [system-bus]
  (let [stop-ref
        (volatile! false)

        thread-ref
        (thread (watch-loop stop-ref system-bus))]

    {::service true
     :system-bus system-bus
     :stop-ref stop-ref
     :thread-ref thread-ref}))

(defn stop [{:keys [stop-ref thread-ref] :as svc}]
  (vreset! stop-ref true)
  (<!! thread-ref))

