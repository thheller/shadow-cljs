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
    [clojure.core.async :as async :refer (go >! <! alt!! >!! <!!)]
    [shadow.core-ext :as core-ext]
    [shadow.cljs.devtools.server.system-bus :as sys-bus]
    [shadow.cljs.devtools.server.repl-system :as repl-system])
  (:import [java.util UUID]))

(defn index-page [req]
  {:status 200
   :body "foo"})

(defn open-file [{:keys [config ring-request] :as req}]

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

(defn tool-forward*
  [{:keys [tool-in] :as state} msg]
  (>!! tool-in msg)
  state)

(defn tool-forward [id]
  (.addMethod process-api-msg id tool-forward*))

(tool-forward ::m/session-start)
(tool-forward ::m/session-eval)

(defn api-ws-loop! [{:keys [repl-system ws-out] :as ws-state}]
  (let [tool-in
        (async/chan 10)

        tool-id
        (str (UUID/randomUUID))

        tool-out
        (repl-system/tool-connect repl-system tool-id tool-in)

        {:keys [subs] :as final-state}
        (loop [{:keys [ws-in] :as ws-state}
               (assoc ws-state
                 :tool-id tool-id
                 :tool-in tool-in)]
          (alt!!
            ws-in
            ([msg]
              (if-not (some? msg)
                ws-state
                (-> ws-state
                    (process-api-msg msg)
                    (recur))))

            tool-out
            ([msg]
              (if-not (some? msg)
                ws-state
                (do (>!! ws-out {::m/op ::m/tool-msg
                                 ::m/tool-msg msg})

                    (recur ws-state))))))]

    (async/close! tool-in)

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

(defn root* [req]
  (http/route req
    (:GET "" index-page)
    (:GET "/project-info" project-info)
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

