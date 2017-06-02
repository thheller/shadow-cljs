(ns shadow.cljs.devtools.server.config-watch
  (:require [shadow.cljs.devtools.config :as config]
            [clojure.java.io :as io]
            [clojure.core.async :as async :refer (thread <!!)]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs.devtools.server.system-msg :as sys-msg]
            ))

(defn- service? [x]
  (and (map? x)
       (::service x)))

(defn get-last-modified []
  (let [file (io/file "shadow-cljs.edn")]
    (if-not (.exists file)
      0
      (.lastModified file))))

(defn watch-loop [stop-ref system-bus]
  (loop [ts
         (get-last-modified)

         config
         (config/load-cljs-edn)]
    (if @stop-ref
      ::stopped
      (let [ts-test (get-last-modified)]
        (if (= ts-test ts)
          (do (Thread/sleep 1000)
              (recur ts config))
          (let [new-config (config/load-cljs-edn)]
            (doseq [{:keys [id] :as new} (-> new-config :builds (vals))
                    :when (not= new (get-in config [:builds id]))]
              (sys-bus/publish! system-bus [::sys-msg/config-watch id] {:config new}))

            (recur ts-test new-config)
            ))))))

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

