(ns shadow.cljs.devtools.server.web.api
  (:require
    [clojure.edn :as edn]
    [clojure.java.shell :as sh]
    [shadow.jvm-log :as log]
    [shadow.cljs.devtools.server.web.common :as common]
    [shadow.cljs.devtools.server.web.graph :as graph]
    [shadow.build.closure :as closure]
    [shadow.http.router :as http]
    [shadow.repl :as repl]
    [shadow.cljs.devtools.server.util :as server-util]
    [shadow.cljs.devtools.api :as api]
    [shadow.cljs.util :as util]
    [shadow.cljs.model :as m]
    [hiccup.page :refer (html5)]
    [clojure.java.io :as io]
    [clojure.core.async :as async :refer (go >! <! >!! <!!)]
    [shadow.core-ext :as core-ext]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]))

(defn index-page [req]
  {:status 200
   :body "foo"})

(defn open-file [{:keys [config ring-request] :as req}]

  (let [data
        (-> req
            (get-in [:ring-request :body])
            (slurp)
            (edn/read-string))

        {:keys [open-file-command]}
        config

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
     :body (core-ext/safe-pr-str result)}))

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

(defn api-ws-loop! [ws-state]
  (let [{:keys [subs] :as final-state}
        (loop [{:keys [ws-in] :as ws-state} ws-state]
          (let [msg (<!! ws-in)]
            (if-not (some? msg)
              ws-state
              (-> ws-state
                  (process-api-msg msg)
                  (recur)))))]

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

(defn root* [req]
  (http/route req
    (:GET "" index-page)
    (:POST "/graph" graph/serve)
    (:POST "/open-file" open-file)
    common/not-found))

(defn root [{:keys [ring-request] :as req}]
  ;; not the cleanest CORS implementation but I want to call API methods from the client
  (let [headers
        {"Access-Control-Allow-Origin" "*"
         "Access-Control-Allow-Headers"
         (or (get-in ring-request [:headers "access-control-request-headers"])
             "content-type")
         "content-type" "application/edn; charset=utf-8"}]

    (if-not (= :options (:request-method ring-request))
      (-> req
          (root*)
          ;; FIXME: can't do this when websockets connect
          (update :headers merge headers))
      {:status 200
       :headers headers
       :body ""}
      )))

