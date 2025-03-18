(ns shadow.cljs.devtools.server.web.api
  (:require
    [clojure.core.async :as async :refer (go >! <! alt!! >!! <!!)]
    [clojure.set :as set]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.http.router :as http]
    [shadow.cljs.devtools.server.util :as server-util]
    [shadow.cljs :as-alias m]
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
     :headers {"content-type" "application/transit+json; charset=utf-8"}
     :body (transit-str result)}))

(defn project-info [{:keys [transit-read transit-str] :as req}]
  {:status 200
   :headers {"content-type" "application/transit+json; charset=utf-8"}
   :body (transit-str (server-util/project-info))})

(defn project-token [req]
  {:status 200
   :headers {"content-type" "text/plain; charset=utf-8"}
   :body (get-in req [:http :server-token])})


(defn ui-init-data [req]
  {:status 410 ;; gone
   :header {"content-type" "text/plain"}
   :body "Your browser requested old data, please hard refresh the UI!"})

(defn root* [req]
  (http/route req
    (:GET "" index-page)
    (:GET "/project-info" project-info)
    (:GET "/token" project-token)
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
  (let [server-token
        (get-in req [:http :server-token])

        trusted?
        (or (= server-token
               (get-in req [:ring-request :query-params "server-token"])))]

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

      ;; relay can be very chatty, try to not drop too many messages
      ;; while also not buffering too much
      (let [ws-in (async/chan 4096 (map transit-read))
            ws-out (async/chan 4096 (map transit-str))]
        {:ws-in ws-in
         :ws-out ws-out
         :ws-loop (relay/connect relay ws-in ws-out {:remote true :websocket true})}))))