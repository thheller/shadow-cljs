(ns shadow.cljs.devtools.client.node
  (:require [shadow.cljs.devtools.client.env :as env]
            ["ws" :as ws]
            [cljs.reader :as reader]
            [goog.object :as gobj]))

(defonce client-id (random-uuid))

(defonce ws-ref (volatile! nil))

(defn ws-close []
  (when-some [tcp @ws-ref]
    (.close tcp)
    (vreset! ws-ref nil)))

(defn ws-msg [msg]
  (when-some [ws @ws-ref]
    (.send ws (pr-str msg)
      (fn [err]
        (when err
          (js/console.error "REPL msg send failed" err))))
    ))

(defn node-eval [{:keys [js source-map-json] :as msg}]
  (let [result (js/SHADOW_NODE_EVAL js source-map-json)]
    result))

(defn is-loaded? [src]
  (true? (gobj/get js/SHADOW_IMPORTED src)))

(defn closure-import [src]
  {:pre [(string? src)]}
  (js/SHADOW_IMPORT src))

(defn repl-init
  [{:keys [id repl-state] :as msg} done]
  (let [{:keys [repl-sources]} repl-state]

    (doseq [{:keys [output-name] :as src} repl-sources
            :when (not (is-loaded? output-name))]
      (closure-import output-name))

    (ws-msg {:type :repl/init-complete :id id})
    (done)
    ))

(defn repl-invoke [{:keys [id] :as msg}]
  (let [result
        (-> (env/repl-call #(node-eval msg) env/repl-error)
            (assoc :id id))]

    (ws-msg result)))

(defn repl-set-ns [{:keys [id] :as msg}]
  ;; nothing for the client to do really
  (ws-msg {:type :repl/set-ns-complete :id id}))

(defn repl-require
  [{:keys [id sources reload-namespaces] :as msg} done]
  (try
    (doseq [{:keys [provides output-name] :as src} sources]
      (when (or (not (is-loaded? output-name))
                (some reload-namespaces provides))
        (closure-import output-name)))
    (ws-msg {:type :repl/require-complete :id id})


    (catch :default e
      (js/console.error "repl/require failed" e)
      (ws-msg {:type :repl/require-error :id id :error (.-message e)})))
  (done))

(defn build-complete
  [{:keys [info reload-info] :as msg}]
  (let [{:keys [sources compiled warnings]} info]

    (when (and env/autoload
               (or (empty? warnings) env/ignore-warnings))

      (let [files-to-require
            (->> sources
                 (remove (fn [{:keys [ns]}]
                           (contains? (:never-load reload-info) ns)))
                 (filter (fn [{:keys [ns resource-id]}]
                           (or (contains? compiled resource-id)
                               (contains? (:always-load reload-info) ns))))
                 (map :output-name)
                 (into []))]

        (when (seq files-to-require)
          (env/do-js-reload
            msg
            #(doseq [src files-to-require]
               (env/before-load-src src)
               (closure-import src))
            ))))))

(defn process-message
  [{:keys [type] :as msg} done]
  ;; (js/console.log "repl-msg" msg)
  (case type
    :repl/init
    (repl-init msg done)

    :repl/invoke
    (repl-invoke msg)

    :repl/set-ns
    (repl-set-ns msg)

    :repl/require
    (repl-require msg done)

    :repl/ping
    (ws-msg {:type :repl/pong :time-server (:time-server msg) :time-runtime (js/Date.now)})

    :build-configure
    :ignored

    :build-start
    :ignored

    :build-complete
    (build-complete msg)

    :build-failure
    :ignored

    :worker-shutdown
    (.terminate @ws-ref)

    ;; default
    (prn [:repl-unknown msg]))

  (when-not (contains? env/async-ops type)
    (done)))

(defn ws-connect []
  (let [url
        (env/ws-url :node)

        client
        (ws. url #js {:rejectUnauthorized false})]

    (.on client "open"
      (fn []
        (vreset! ws-ref client)))

    (.on client "unexpected-response"
      (fn [req ^js res]
        (let [status (.-statusCode res)]
          (if (= 406 status)
            (js/console.log "REPL connection rejected, probably stale JS connecting to new server.")
            (js/console.log "REPL unexpected error" (.-statusCode res))
            ))))

    (.on client "message"
      (fn [data flags]
        (try
          (env/process-ws-msg data process-message)
          (catch :default e
            (js/console.error "failed to process message" data e)))))

    (.on client "close"
      (fn []
        (js/console.log "REPL client disconnected")
        ))

    (.on client "error"
      (fn [err]
        (js/console.log "REPL client error" err)))
    ))

(when env/enabled
  (ws-close) ;; if this is reloaded, reconnect the socket
  (ws-connect))

