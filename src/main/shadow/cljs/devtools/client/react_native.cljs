(ns shadow.cljs.devtools.client.react-native
  (:require
    [shadow.cljs.devtools.client.env :as env]
    [shadow.remote.runtime.api :as api]
    [shadow.remote.runtime.shared :as shared]
    [shadow.cljs.devtools.client.shared :as cljs-shared]
    [shadow.cljs.devtools.client.websocket :as ws]
    [clojure.string :as str]))

(defn devtools-msg
  ([x]
   (when env/log
     (js/console.log "shadow-cljs" x)))
  ([x y]
   (when env/log
     (js/console.log "shadow-cljs" x y))))

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
          (cljs-shared/load-sources runtime sources-to-get #(do-js-reload msg % noop))
          )))))

(defn global-eval [js]
  (if (not= "undefined" (js* "typeof(module)"))
    ;; don't eval in the global scope in case of :npm-module builds running in webpack
    (js/eval js)
    ;; hack to force eval in global scope
    ;; goog.globalEval doesn't have a return value so can't use that for REPL invokes
    (js* "(0,eval)(~{});" js)))

(defn attempt-to-find-host [start-fn [host & more-hosts]]
  (if-not host
    (js/console.error (str "Could not find shadow-cljs host address, tried " env/server-hosts))
    (let [controller
          (js/AbortController.)

          timeout-id
          (js/setTimeout
            (fn []
              (.abort controller))
            env/connect-timeout)

          success
          (fn []
            (js/clearTimeout timeout-id)
            (set! env/selected-host host)
            (start-fn))

          fail
          (fn []
            (js/clearTimeout timeout-id)
            (attempt-to-find-host start-fn more-hosts))]

      (-> (js/fetch (str (env/get-server-protocol) "://" host ":" env/server-port "/api/project-info")
            #js {:signal (.-signal controller)})
          (.then
            (fn [^js resp]
              (if (.-ok resp)
                (success)
                (fail))))
          (.catch fail)))))

(when (and env/enabled (pos? env/worker-client-id))

  (extend-type cljs-shared/Runtime
    api/IEvalJS
    (-js-eval [this code success fail]
      (try
        (success (global-eval code))

        (catch :default e
          (fail e))))

    cljs-shared/IHostSpecific
    (do-invoke [this ns {:keys [js] :as _} success fail]
      (try
        (success (global-eval js))
        (catch :default e
          (fail e))))

    (do-repl-init [runtime {:keys [repl-sources]} done error]
      (cljs-shared/load-sources
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

  (cljs-shared/add-plugin! ::client #{}
    (fn [{:keys [runtime] :as env}]
      (let [svc {:runtime runtime}]
        (api/add-extension runtime ::client
          {:on-welcome
           (fn []
             ;; FIXME: why does this break stuff when done when the namespace is loaded?
             ;; why does it have to wait until the websocket is connected?
             (env/patch-goog!)
             (devtools-msg (str "#" (-> runtime :state-ref deref :client-id) " ready!")))

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


  ;; delay connecting for a little bit so errors thrown here don't disturb the app during load
  ;; don't know when errors can actually happen but networks errors somehow seem to break the app
  (js/setTimeout
    (fn []
      (let [start-fn #(cljs-shared/init-runtime! {:host :react-native} ws/start ws/send ws/stop)]

        ;; try all known server ips if no specific host is configured
        ;; host addr may change at any time eg. wlan switching, different devices
        ;; so only making them sticky while the app is running, will reset on app reload
        (cond
          env/selected-host
          (start-fn)

          (and (seq env/server-host) (not= "localhost" env/server-host))
          (start-fn)

          (seq env/server-hosts)
          (attempt-to-find-host
            start-fn
            (->> (str/split env/server-hosts ",")
                 (vec)))

          :else
          (start-fn)
          )))
    250
    ))