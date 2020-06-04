(ns shadow.cljs.devtools.client.react-native
  (:require
    [shadow.cljs.devtools.client.env :as env]
    [shadow.remote.runtime.api :as api]
    [shadow.remote.runtime.shared :as shared]
    [shadow.cljs.devtools.client.shared :as cljs-shared]
    [shadow.cljs.devtools.client.websocket :as ws]))

(defn devtools-msg
  ([x]
   (js/console.log x))
  ([x y]
   (js/console.log x y)))

(defn script-eval [code]
  (js/goog.global.eval code))

(defn do-js-load [sources]
  (doseq [{:keys [resource-name js] :as src} sources]
    (devtools-msg "load JS" resource-name)
    (env/before-load-src src)
    (script-eval (str js "\n//# sourceURL=" resource-name))))

(defn do-js-reload [msg sources complete-fn]
  (env/do-js-reload
    (assoc msg
      :log-missing-fn
      (fn [fn-sym]
        (devtools-msg (str "can't find fn " fn-sym)))
      :log-call-async
      (fn [fn-sym]
        (devtools-msg (str "call async " fn-sym)))
      :log-call
      (fn [fn-sym]
        (devtools-msg (str "call " fn-sym))))
    #(do-js-load sources)
    complete-fn))

(defn noop [& args])

(defn handle-build-complete [runtime {:keys [info reload-info] :as msg}]
  (let [{:keys [sources compiled warnings]} info]

    (when (and env/autoload
               (or (empty? warnings) env/ignore-warnings))

      (let [sources-to-get (env/filter-reload-sources info reload-info)]

        (when (seq sources-to-get)
          (ws/load-sources runtime sources-to-get #(do-js-reload msg % noop))
          )))))

(defn global-eval [js]
  (if (not= "undefined" (js* "typeof(module)"))
    ;; don't eval in the global scope in case of :npm-module builds running in webpack
    (js/eval js)
    ;; hack to force eval in global scope
    ;; goog.globalEval doesn't have a return value so can't use that for REPL invokes
    (js* "(0,eval)(~{});" js)))



(defn start []
  (let [ws-url (env/get-ws-relay-url)]
    (ws/start ws-url {:host :react-native})))

(when (and env/enabled (pos? env/worker-client-id))

  (extend-type cljs-shared/Runtime
    api/IEvalJS
    (-js-eval [this code]
      (global-eval code))

    cljs-shared/IHostSpecific
    (do-invoke [this {:keys [js] :as _}]
      (global-eval js))

    (do-repl-init [runtime {:keys [repl-sources]} done error]
      (ws/load-sources
        runtime
        ;; maybe need to load some missing files to init REPL
        (->> repl-sources
             (remove env/src-is-loaded?)
             (into []))
        (fn [sources]
          (do-js-load sources)
          (done))))

    (do-repl-require [runtime {:keys [sources reload-namespaces js-requires] :as msg} done error]
      (let [sources-to-load
            (->> sources
                 (remove (fn [{:keys [provides] :as src}]
                           (and (env/src-is-loaded? src)
                                (not (some reload-namespaces provides)))))
                 (into []))]

        (if-not (seq sources-to-load)
          (done [])
          (shared/call runtime
            {:op :cljs-load-sources
             :to env/worker-client-id
             :sources (into [] (map :resource-id) sources-to-load)}

            {:cljs-sources
             (fn [{:keys [sources] :as msg}]
               (try
                 (do-js-load sources)
                 (done sources-to-load)
                 (catch :default ex
                   (error ex))))})))))

  (cljs-shared/init-extension! ::client #{}
    (fn [{:keys [runtime] :as env}]
      (let [svc {:runtime runtime}]
        (api/add-extension runtime ::client
          {:on-connect
           (fn []
             ;; FIXME: why does this break stuff when done when the namespace is loaded?
             ;; why does it have to wait until the websocket is connected?
             (env/patch-goog!)
             (devtools-msg "ready!"))

           :on-disconnect
           (fn []
             (js/console.warn "The shadow-cljs Websocket was disconnected."))

           :ops
           {:access-denied
            (fn [msg]
              (js/console.error
                (str "Stale Output! Your loaded JS was not produced by the running shadow-cljs instance."
                     " Is the watch for this build running?")))

            :cljs-repl-ping
            #(cljs-shared/cljs-repl-ping runtime %)

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
                (js/console.warn "The watch for this build was stopped!")

                ;; FIXME: what are the downside to just resuming on that worker?
                ;; can't know if it changed something in the build
                ;; all previous analyzer state is gone and might be out of sync with this instance
                (= :client-connect event-op)
                (js/console.warn "The watch for this build was restarted. Reload required!")
                ))}})
        svc))

    (fn [{:keys [runtime] :as svc}]
      (api/del-extension runtime ::client)))

  ;; delay connecting other page stuff has a chance to finish first
  (js/setTimeout start 100))