(ns shadow.cljs.devtools.client.node-esm
  (:require
    ["ws$default" :as ws]
    [cljs.reader :as reader]
    [clojure.string :as str]
    [goog.object :as gobj]
    [shadow.remote.runtime.shared :as shared]
    [shadow.esm :as esm]
    [shadow.cljs.devtools.client.shared :as cljs-shared]
    [shadow.cljs.devtools.client.env :as env]
    [shadow.remote.runtime.api :as api]))


;; FIXME: this is loaded via cljs_env.js
;; but why is it listed in repl-sources in repl-init?
;; this just stops it from being loaded again
(js/SHADOW_ENV.setLoaded "goog.base.js")

(defn is-loaded? [src]
  (js/SHADOW_ENV.isLoaded src))

(defn load-sources [files-to-load finished error]
  (let [src (first files-to-load)]
    (if-not src
      (finished)
      (let [{:keys [output-name]} src
            path (str "./cljs-runtime/" output-name "?rand=" (rand))]
        (js/console.log "loading" output-name)
        (env/before-load-src src)

        ;; (js/console.log "repl loading" path)
        ;; at which point does not start choking when running import over and over again?
        (-> (esm/dynamic-import path)
            ;; loaded things into global scope, so no need for result anywhere
            (.then (fn [_]
                     (load-sources (rest files-to-load) finished error)))
            ;; load errors most likely caused by exception thrown during load
            (.catch (fn [e]
                      (error e src))))))))

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
                 (into []))]

        (when (seq files-to-require)
          (env/do-js-reload
            msg
            (fn [continue]
              (load-sources files-to-require continue
                (fn [e src]
                  (js/console.error "failed to load" (:output-name src) e))))
            ))))))

(def client-info
  {:host :node-esm
   :desc (str "Node " js/process.version)})

(defn start [runtime]
  (let [ws-url
        (env/get-ws-relay-url)

        socket
        (ws. ws-url #js {:rejectUnauthorized false})

        ws-active-ref
        (atom true)]

    (.on socket "message"
      (fn [data]
        (when @ws-active-ref
          (cljs-shared/remote-msg runtime data))))

    (.on socket "open"
      (fn [e]
        (when @ws-active-ref
          (cljs-shared/remote-open runtime e))))

    (.on socket "close"
      (fn [e]
        (when @ws-active-ref
          (cljs-shared/remote-close runtime e ws-url))))

    (.on socket "error"
      (fn [e]
        (when @ws-active-ref
          (cljs-shared/remote-error runtime e))))

    {:socket socket
     :ws-active-ref ws-active-ref}))

(defn send [{:keys [socket]} msg]
  (.send socket msg))

(defn stop [{:keys [socket ws-active-ref]}]
  (reset! ws-active-ref false)
  (.close socket))

(comment
  ;; maybe use this instead of plain eval since we can maybe get source maps working this way?
  (defn eval-js [code]
    (esm/dynamic-import
      (str "data:text/javascript;charset=utf-8;base64,"
           (-> (js/Buffer.from code)
               (.toString "base64"))))))

(defn eval-js [js]
  ;; hack to force eval in global scope
  ;; goog.globalEval doesn't have a return value so can't use that for REPL invokes
  (js* "(0,eval)(~{});" js))

;; want things to start when this ns is in :preloads
(when (pos? env/worker-client-id)
  (extend-type cljs-shared/Runtime
    api/IEvalJS
    (-js-eval [this code success fail]
      (try
        (success (eval-js code))
        (catch :default e
          (fail e code))))

    cljs-shared/IHostSpecific
    (do-invoke [this ns {:keys [js] :as msg} success fail]
      (try
        (success (eval-js js))
        (catch :default e
          (fail e msg))))

    (do-repl-init [runtime {:keys [repl-sources]} done error]
      (load-sources
        (->> repl-sources
             (remove (fn [{:keys [output-name] :as src}]
                       (is-loaded? output-name)))
             (vec))
        done
        error))

    (do-repl-require [this {:keys [sources reload-namespaces] :as msg} done error]
      (load-sources
        (->> sources
             (filter (fn [{:keys [provides output-name] :as src}]
                       (or (not (is-loaded? output-name))
                           (some reload-namespaces provides))))
             (vec))
        done
        error)))

  (cljs-shared/add-plugin! ::client #{}
    (fn [{:keys [runtime] :as env}]
      (let [svc {:runtime runtime}]
        (api/add-extension runtime ::client
          {:on-welcome
           (fn []
             ;; FIXME: why does this break stuff when done when the namespace is loaded?
             ;; why does it have to wait until the websocket is connected?
             (env/patch-goog!)
             (when env/log
               (js/console.log (str "shadow-cljs - #" (-> runtime :state-ref deref :client-id) " ready!"))))

           :on-disconnect
           (fn []
             (js/console.warn "The shadow-cljs Websocket was disconnected."))

           :ops
           {:access-denied
            (fn [msg]
              (js/console.error
                (str "Stale Output! Your loaded JS was not produced by the running shadow-cljs instance."
                     " Is the watch for this build running?")))

            :cljs-build-configure
            (fn [msg])

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

  (cljs-shared/init-runtime! client-info start send stop))