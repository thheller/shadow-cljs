(ns shadow.cljs.devtools.client.node
  (:require
    ["ws" :as ws]
    [cljs.reader :as reader]
    [goog.object :as gobj]
    [shadow.remote.runtime.shared :as shared]
    [shadow.cljs.devtools.client.shared :as cljs-shared]
    [shadow.cljs.devtools.client.env :as env]
    [shadow.remote.runtime.api :as api]))

(defn node-eval [{:keys [js source-map-json] :as msg}]
  (let [result (js/SHADOW_NODE_EVAL js source-map-json)]
    result))

(defn is-loaded? [src]
  (true? (gobj/get js/SHADOW_IMPORTED src)))

(defn closure-import [src]
  {:pre [(string? src)]}
  (js/SHADOW_IMPORT src))

(defn handle-build-complete
  [runtime {:keys [info reload-info] :as msg}]
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

(defn start []
  (let [ws-url
        (env/get-ws-relay-url)

        socket
        (ws. ws-url #js {:rejectUnauthorized false})

        state-ref
        (-> {:type :runtime
             :lang :cljs
             :build-id (keyword env/build-id)
             :proc-id env/proc-id
             :host :node
             :node-version js/process.version}
            (shared/init-state)
            (atom))

        send-fn
        (fn [msg]
          (.send socket msg))

        runtime
        (cljs-shared/Runtime.
          state-ref
          send-fn
          #(.close socket))]

    (cljs-shared/init-runtime! runtime)

    (.on socket "message"
      (fn [data]
        (let [msg (cljs-shared/transit-read data)]
          (shared/process runtime msg))))

    (.on socket "open"
      (fn [e]
        ;; FIXME: should this do something on-connect?
        ;; first message is either hello or some kind of rejection
        ))

    (.on socket "close"
      (fn [e]
        (cljs-shared/stop-runtime! e)))

    (.on socket "error"
      (fn [e]
        (js/console.warn "ws-error" e)))))

;; want things to start when this ns is in :preloads
(when (pos? env/worker-client-id)

  (extend-type cljs-shared/Runtime
    api/IEvalJS
    (-js-eval [this code]
      (js/SHADOW_NODE_EVAL code))

    cljs-shared/IHostSpecific
    (do-invoke [this msg]
      (node-eval msg))

    (do-repl-init [runtime {:keys [repl-sources]} done error]
      (try
        (doseq [{:keys [output-name] :as src} repl-sources
                :when (not (is-loaded? output-name))]
          (closure-import output-name))

        (done)
        (catch :default e
          (error e))))

    (do-repl-require [this {:keys [sources reload-namespaces] :as msg} done error]
      (try
        (doseq [{:keys [provides output-name] :as src} sources]
          (when (or (not (is-loaded? output-name))
                    (some reload-namespaces provides))
            (closure-import output-name)))

        (done)
        (catch :default e
          (error e)))))

  (cljs-shared/init-extension! ::client #{}
    (fn [{:keys [runtime] :as env}]
      (let [svc {:runtime runtime}]
        (api/add-extension runtime ::client
          {:on-welcome
           (fn []
             ;; FIXME: why does this break stuff when done when the namespace is loaded?
             ;; why does it have to wait until the websocket is connected?
             (env/patch-goog!)
             (js/console.log (str "shadow-cljs - #"  (-> runtime :state-ref deref :client-id) " ready!")))

           :on-disconnect
           (fn []
             (js/console.warn "The shadow-cljs Websocket was disconnected."))

           :ops
           {:access-denied
            (fn [msg]
              (js/console.error
                (str "Stale Output! Your loaded JS was not produced by the running shadow-cljs instance."
                     " Is the watch for this build running?")))

            :cljs-build-start
            (fn [msg]
              ;; (js/console.log "cljs-build-start" msg)
              (env/run-custom-notify! (assoc msg :type :build-start)))

            :cljs-build-complete
            (fn [msg]
              ;; (js/console.log "cljs-build-complete" msg)
              (let [msg (env/add-warnings-to-info msg)]
                (handle-build-complete runtime msg)
                (env/run-custom-notify! (assoc msg :type :build-complete))))

            :cljs-build-failure
            (fn [msg]
              ;; (js/console.log "cljs-build-failure" msg)
              (env/run-custom-notify! (assoc msg :type :build-failure)))

            ::env/worker-notify
            (fn [{:keys [event-op client-id]}]
              (cond
                (and (= :client-disconnect event-op)
                     (= client-id env/worker-client-id))
                (js/console.warn "shadow-cljs - The watch for this build was stopped!")

                (= :client-connect event-op)
                (js/console.warn "shadow-cljs - A new watch for this build was started, restart of this process required!")

                :else
                nil))
            }})
        svc))

    (fn [{:keys [runtime] :as svc}]
      (api/del-extension runtime ::client)))

  (start))
