(ns shadow.cljs.devtools.server.config-watch
  (:require [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.server.env :as env]
            [clojure.java.io :as io]
            [clojure.core.async :as async :refer (thread <!!)]
            [shadow.cljs.devtools.server.sync-db :as sync-db]
            [shadow.cljs.devtools.server.system-bus :as sys-bus]
            [shadow.cljs :as-alias m]
            [shadow.jvm-log :as log]))

(defn- service? [x]
  (and (map? x)
       (::service x)))

(defn get-last-modified []
  (let [file (io/file "shadow-cljs.edn")]
    (if-not (.exists file)
      0
      (.lastModified file))))

(defn sync-builds [sync-db builds]
  (sync-db/update! sync-db update ::m/build
    (fn [current]
      (reduce-kv
        (fn [table build-id build-config]
          (let [prev-entry (get current build-id)]
            (cond
              ;; new build config added
              (not prev-entry)
              (assoc table build-id {::m/build-id build-id
                                     ::m/build-config-raw build-config})

              ;; diffs a bit faster if we keep the old entry
              (= (::m/build-config-raw prev-entry) build-config)
              (assoc table build-id prev-entry)

              ;; preserve other fields while updating config
              :else
              (assoc table build-id (assoc prev-entry ::m/build-config-raw build-config))
              )))
        ;; starting with empty instead of updating table
        ;; so that deleted configs are actually deleted
        {}
        builds))))

(defn watch-loop [stop-ref system-bus sync-db]
  (let [{:keys [dependencies] :as config}
        (config/load-cljs-edn)]

    (sync-builds sync-db (:builds config))

    (loop [ts (get-last-modified)
           config config]

      (if @stop-ref
        ::stopped
        (let [ts-test (get-last-modified)]
          (if (= ts-test ts)
            (do (Thread/sleep 1000)
                (recur ts config))

            (let [new-config
                  (try
                    (let [new-config (config/load-cljs-edn)]
                      (log/debug ::trigger)

                      (when (not= new-config config)
                        (log/debug ::update-global)
                        (sys-bus/publish! system-bus ::m/config-watch {:config new-config}))

                      (doseq [{:keys [build-id] :as new} (-> new-config :builds (vals))
                              :when (not= new (get-in config [:builds build-id]))]
                        (log/debug ::update-build {:build-id build-id})
                        (sys-bus/publish! system-bus [::m/config-watch build-id] {:config new}))

                      (sync-builds sync-db (:builds new-config))

                      (reset! env/dependencies-modified-ref (not= dependencies (:dependencies new-config))))
                    (catch Exception e
                      (log/warn-ex e ::update)
                      config))]

              (recur ts-test new-config))))))))

(defn start [system-bus sync-db]
  (let [stop-ref
        (volatile! false)

        thread-ref
        (thread (watch-loop stop-ref system-bus sync-db))]

    {::service true
     :system-bus system-bus
     :stop-ref stop-ref
     :thread-ref thread-ref}))

(defn stop [{:keys [stop-ref thread-ref] :as svc}]
  (vreset! stop-ref true)
  (<!! thread-ref))

