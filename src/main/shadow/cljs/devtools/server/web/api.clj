(ns shadow.cljs.devtools.server.web.api
  (:require
    [clojure.core.async :as async :refer (go >! <! alt!! >!! <!!)]
    [clojure.set :as set]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.http.router :as http]
    [shadow.cljs.devtools.server.util :as server-util]
    [shadow.cljs.model :as m]
    [shadow.core-ext]
    [shadow.remote.relay.api :as relay]
    [shadow.cljs.devtools.server.supervisor :as super]
    [shadow.cljs.devtools.config :as config]))

(defn index-page [req]
  {:status 200
   :body "foo"})

(defn open-file [{:keys [config transit-read transit-str] :as req}]
  (let [data
        (-> req
            (get-in [:ring-request :body])
            (slurp)
            (transit-read))

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

(defn project-info [{:keys [transit-read transit-str] :as req}]
  {:status 200
   :headers {"content-type" "application/transit+json; charset=utf-8"}
   :body (transit-str (server-util/project-info))})

(defn ui-init-data [{:keys [dev-http transit-str supervisor] :as req}]
  {:status 200
   :header {"content-type" "application/transit+json"}
   :body
   (transit-str
     {::m/http-servers
      (->> (:servers @dev-http)
           (map-indexed
             (fn [idx {:keys [http-url https-url config]}]
               {::m/http-server-id idx
                ::m/http-url http-url
                ::m/http-config config
                ::m/https-url https-url}))
           (into []))

      ::m/build-configs
      (let [{:keys [builds]}
            (config/load-cljs-edn)]

        (->> (vals builds)
             (sort-by :build-id)
             (remove #(-> % meta :generated))
             (map (fn [{:keys [build-id target] :as config}]
                    {::m/build-id build-id
                     ::m/build-target target
                     ::m/build-worker-active (some? (super/get-worker supervisor build-id))
                     ::m/build-config-raw config}))
             (into [])))})})

(defn root* [req]
  (http/route req
    (:GET "" index-page)
    (:GET "/project-info" project-info)
    (:GET "/ui-init-data" ui-init-data)
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
      ;; this sucks too, client gets open first then followed by close
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