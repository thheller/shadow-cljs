(ns shadow.cljs.devtools.server.web.api
  (:require
    [clojure.edn :as edn]
    [clojure.java.shell :as sh]
    [clojure.java.io :as io]
    [clojure.core.async :as async :refer (go >! <! alt!! >!! <!!)]
    [clojure.set :as set]
    [hiccup.page :refer (html5)]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.cljs.devtools.graph :as graph]
    [shadow.build.closure :as closure]
    [shadow.http.router :as http]
    [shadow.cljs.devtools.server.util :as server-util]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.util :as util]
    [shadow.cljs.model :as m]
    [shadow.debug :refer (?> ?-> ?->>)]
    [shadow.core-ext]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.remote.relay.api :as relay]
    [shadow.cljs.devtools.errors :as errors]
    [shadow.build.warnings :as warnings]
    [shadow.build :as build]
    [shadow.build.api :as build-api]
    [shadow.cljs.devtools.config :as config]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.server.worker :as worker]
    [shadow.build.log :as build-log]
    ))

(defn index-page [req]
  {:status 200
   :body "foo"})

(defn open-file [{:keys [config transit-str] :as req}]

  (let [data
        (-> req
            (get-in [:ring-request :body])
            (slurp)
            (edn/read-string))

        open-file-command
        (or (:open-file-command config)
            (get-in config [:user-config :open-file-command]))

        result
        (try
          (if-not open-file-command
            {:exit 1
             :err "no :open-file-command"}
            (let [launch-args
                  (server-util/make-open-args data open-file-command)

                  result
                  (server-util/launch launch-args {})]

              (log/debug ::open-file {:launch-args launch-args :result result})
              result))
          (catch Exception e
            {:type :error
             :ex-msg (.getMessage e)
             :ex-data (ex-data e)}))]

    {:status 200
     :headers {"content-type" "application/edn; charset=utf-8"}
     :body (transit-str result)}))

(defmulti process-api-msg (fn [state msg] (::m/op msg)) :default ::default)

(defmethod process-api-msg ::default
  [{:keys [ws-out] :as state} msg]
  (log/warn ::unknown-msg {:msg msg})
  (>!! ws-out {::m/op ::m/unknown-msg
               ::m/input msg})
  state)

(defmethod process-api-msg ::m/subscribe
  [{:keys [system-bus ws-out] :as state} {::m/keys [topic]}]
  (let [sub-chan
        (-> (async/sliding-buffer 100)
            (async/chan))]

    (log/debug ::ws-subscribe {:topic topic})

    (sys-bus/sub system-bus topic sub-chan)

    (go (loop []
          (when-some [msg (<! sub-chan)]
            ;; msg already contains ::m/topic due to sys-bus
            (>! ws-out (assoc msg ::m/op ::m/sub-msg))
            (recur)))

        (>! ws-out {::m/op ::m/sub-close
                    ::m/topic topic}))

    (update state ::subs conj sub-chan)))

(defmethod process-api-msg ::m/build-watch-start!
  [{:keys [supervisor] :as state} {::m/keys [build-id]}]
  (let [config (config/get-build build-id)
        ;; FIXME: needs access to cli opts used to start server?
        worker (super/start-worker supervisor config {})]
    (worker/start-autobuild worker))
  state)

(defmethod process-api-msg ::m/build-watch-stop!
  [{:keys [supervisor] :as state} {::m/keys [build-id]}]
  (super/stop-worker supervisor build-id)
  state)

(defmethod process-api-msg ::m/build-watch-compile!
  [{:keys [supervisor] :as state} {::m/keys [build-id]}]
  (let [worker (super/get-worker supervisor build-id)]
    (worker/compile worker))
  state)

(defn do-build [{:keys [system-bus] :as state} build-id mode cli-opts]
  (future
    (let [build-config
          (config/get-build build-id)

          build-logger
          (reify
            build-log/BuildLog
            (log*
              [_ state event]
              (sys-bus/publish system-bus ::m/build-log
                {:type :build-log
                 :build-id build-id
                 :event event})))

          pub-msg
          (fn [msg]
            ;; FIXME: this is not worker output but adding an extra channel seems like overkill
            (sys-bus/publish system-bus ::m/worker-broadcast msg)
            (sys-bus/publish system-bus [::m/worker-output build-id] msg))]
      (try
        ;; not at all useful to send this message but want to match worker message flow for now
        (pub-msg {:type :build-configure
                  :build-id build-id
                  :build-config build-config})

        (pub-msg {:type :build-start
                  :build-id build-id})

        (let [build-state
              (-> (server-util/new-build build-config mode {})
                  (build-api/with-logger build-logger)
                  (build/configure mode build-config cli-opts)
                  (build/compile)
                  (cond->
                    (= :release mode)
                    (build/optimize))
                  (build/flush))]

          (pub-msg {:type :build-complete
                    :build-id build-id
                    :info (::build/build-info build-state)}))

        (catch Exception e
          (pub-msg {:type :build-failure
                    :build-id build-id
                    :report (binding [warnings/*color* false]
                              (errors/error-format e))
                    }))
        ))))

(defmethod process-api-msg ::m/build-compile!
  [env {::m/keys [build-id]}]
  (do-build env build-id :dev {})
  {::m/build-id build-id}
  env)

(defmethod process-api-msg ::m/build-release!
  [env {::m/keys [build-id]}]
  (do-build env build-id :release {})
  {::m/build-id build-id}
  env)

(defmethod process-api-msg ::m/build-release-debug!
  [env {::m/keys [build-id]}]
  (do-build env build-id :release {:config-merge [{:compiler-options {:pseudo-names true :pretty-print true}}]})
  {::m/build-id build-id}
  env)

(defn api-ws-loop! [{:keys [ws-in ws-out] :as ws-state}]
  (let [{:keys [subs] :as final-state}
        (loop [ws-state ws-state]
          (when-some [msg (<!! ws-in)]
            (-> ws-state
                (process-api-msg msg)
                (recur))))]

    (async/close! ws-out)

    (doseq [sub subs]
      (async/close! sub))

    ::done))

(defn api-ws [{:keys [transit-read transit-str] :as req}]
  ;; FIXME: negotiate encoding somehow? could just as well use edn
  (let [ws-in (async/chan 10 (map transit-read))
        ws-out (async/chan 10 (map transit-str))]
    {:ws-in ws-in
     :ws-out ws-out
     :ws-loop
     (async/thread
       (api-ws-loop! (assoc req
                       ::subs []
                       :ws-in ws-in
                       :ws-out ws-out)))}))

(defn project-info [{:keys [transit-read transit-str] :as req}]
  (let [project-config
        (-> (io/file "shadow-cljs.edn")
            (.getAbsoluteFile))

        project-home
        (-> project-config
            (.getParentFile)
            (.getAbsolutePath))]

    {:status 200
     :headers {"content-type" "application/transit+json; charset=utf-8"}
     :body (transit-str {:project-config (.getAbsolutePath project-config)
                         :project-home project-home
                         :version (server-util/find-version)})}))

(defn graph-serve [{:keys [transit-read transit-str] :as req}]
  (let [query
        (-> (get-in req [:ring-request :body])
            (transit-read))

        result
        (graph/parser req query)]

    {:status 200
     :header {"content-type" "application/transit+json"}
     :body (transit-str result)}
    ))

(defn root* [req]
  (http/route req
    (:GET "" index-page)
    (:GET "/project-info" project-info)
    (:POST "/graph" graph-serve)
    (:POST "/open-file" open-file)
    common/not-found))

(defn root [{:keys [ring-request] :as req}]
  ;; not the cleanest CORS implementation but I want to call API methods from the client
  (let [headers
        {"Access-Control-Allow-Origin" "*"
         "Access-Control-Allow-Headers"
         (or (get-in ring-request [:headers "access-control-request-headers"])
             "content-type")
         "content-type" "application/transit+json; charset=utf-8"}]

    (if-not (= :options (:request-method ring-request))
      (-> req
          (root*)
          ;; FIXME: can't do this when websockets connect
          (update :headers merge headers))
      {:status 200
       :headers headers
       :body ""}
      )))

(defn api-remote-relay [{:keys [relay transit-read transit-str] :as req}]
  ;; FIXME: negotiate encoding somehow? could just as well use edn
  (let [remote-addr
        (get-in req [:ring-request :remote-addr])

        trusted-hosts
        (set/union
          #{"127.0.0.1"
            "0:0:0:0:0:0:0:1"
            "localhost"}
          (set (get-in req [:config :trusted-hosts]))
          (set (get-in req [:config :user-config :trusted-hosts])))

        server-token
        (get-in req [:http :server-token])

        trusted?
        (or (= server-token
               (get-in req [:ring-request :query-params "server-token"]))

            ;; cannot trust websocket connections even from localhost
            ;; a hostile page might include WebSocket("ws://localhost:9630/...")
            ;; could check Origin but not sure how much that can be trusted either
            ;; so all relay clients should send this token as part of their request

            #_(contains? trusted-hosts remote-addr))]

    ;; FIXME:
    (if-not trusted?
      ;; FIXME: need a better way to return errors
      ;; this isn't possible to detect in JS since spec forbids exposing the status code
      #_{:status 403
         :headers {"content-type" "text/plain"}
         :body (str remote-addr " not trusted. Add :trusted-hosts #{" (pr-str remote-addr) "} in shadow-cljs.edn to trust.")}
      ;; this sucks too, client gets first then followed by close
      ;; {:ws-reject [4000 "Invalid server-token."]}
      ;; so we just send an access denied message and disconnect
      (let [ws-in (async/chan)
            ws-out (async/chan 1 (map transit-str))]
        {:ws-in ws-in
         :ws-out ws-out
         :ws-loop
         (async/go
           (async/>! ws-out {:op :access-denied})
           :access-denied)})

      (let [ws-in (async/chan 10 (map transit-read))
            ws-out (async/chan 256 (map transit-str))]
        {:ws-in ws-in
         :ws-out ws-out
         :ws-loop (relay/connect relay ws-in ws-out {:remote true :websocket true})}))))