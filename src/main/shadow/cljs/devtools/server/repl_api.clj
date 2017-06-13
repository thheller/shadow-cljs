(ns shadow.cljs.devtools.server.repl-api
  (:require [shadow.cljs.devtools.config :as config]
            [shadow.cljs.devtools.server.supervisor :as super]
            [shadow.cljs.devtools.server.worker :as worker]
            [shadow.cljs.devtools.api :as api]
            [clojure.core.server :as srv]
            [clojure.java.io :as io]))

(def ^:dynamic *app* nil)

(defn app []
  (when (nil? *app*)
    (throw (ex-info "missing *app* binding" {})))

  *app*)

(defn start-worker
  "starts a dev worker process for a given :build-id
  opts defaults to {:autobuild true}"

  ([build-id]
   (start-worker build-id {:autobuild true}))
  ([build-id opts]
   (let [{:keys [autobuild]}
         opts

         build-config
         (if (map? build-id)
           build-id
           (config/get-build! build-id))

         {:keys [out supervisor] :as app}
         (app)]

     (if-let [worker (super/get-worker supervisor build-id)]
       (when autobuild
         (worker/start-autobuild worker))

       (-> (super/start-worker supervisor build-config)
           (worker/watch out false)
           (cond->
             autobuild
             (worker/start-autobuild))
           ;; FIXME: sync to ensure the build finished before start-worker returns?
           ;; (worker/sync!)
           )))
   :started))

(defn stop-worker [build-id]
  (let [{:keys [supervisor] :as app}
        (app)]
    (super/stop-worker supervisor build-id)
    :stopped))

(defn repl [build-id]
  (let [{:keys [supervisor] :as app}
        (app)

        worker
        (super/get-worker supervisor build-id)]
    (if-not worker
      :no-worker
      (api/stdin-takeover! worker app))))

(defn node-repl []
  (api/node-repl* (app) {}))

(defn once [build-id]
  (api/once build-id))

(defn release
  ([build-id]
   (api/release build-id {}))
  ([build-id opts]
   (api/release build-id opts)))

(defn check [build-id]
  (api/check build-id))

(defn help []
  (-> (slurp (io/resource "shadow/txt/repl-help.txt"))
      (println)))