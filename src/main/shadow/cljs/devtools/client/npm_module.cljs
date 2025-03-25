(ns shadow.cljs.devtools.client.npm-module
  (:require
    [clojure.string :as str]
    [shadow.cljs.devtools.client.env :as env]
    [shadow.cljs.devtools.client.console]
    [shadow.cljs.devtools.client.websocket :as ws]
    [shadow.cljs.devtools.client.shared :as cljs-shared]
    [shadow.remote.runtime.api :as api]
    ["ws" :as ws-impl]))

;; node specific REPL impl for :npm-module builds
;; can't use generic node impl because the :node-script/:node-library builds
;; have different assumptions about the world that are not true for :npm-module
;; they live in a single file while :npm-module has a file per ns

(defn devtools-msg [msg & args]
  (when env/log
    (js/console.log.apply js/console (into-array (into [(str "shadow-cljs: " msg)] args)))))

(defn do-js-load [sources]
  (doseq [{:keys [output-name]} sources]
    (js-delete js/require.cache (str js/__dirname "/" output-name)))

  (doseq [{:keys [resource-id output-name resource-name js] :as src} sources]
    ;; should really stop using this and rather maintain our own record
    ;; but without this hot-reload will reload shadow-js files with each cycle
    ;; since they don't set it
    (js/$CLJS.SHADOW_ENV.setLoaded output-name)

    (devtools-msg "load JS" resource-name)
    (env/before-load-src src)
    (try
      (js/require (str "./" output-name))
      (catch :default e
        (when env/log
          (js/console.error (str "Failed to load " resource-name) e))
        (throw (js/Error. (str "Failed to load " resource-name ": " (.-message e))))))))

(defn do-js-reload [msg sources]
  (env/do-js-reload
    (assoc msg
      :log-missing-fn
      ;; FIXME: this gets noisy when using web-workers and either main or the workers not having certain code loaded
      ;; should properly filter hook-fns and only attempt to call those that actually apply
      ;; but thats a bit of work since we don't currently track the namespaces that are loaded.
      (fn [fn-sym]
        #_(devtools-msg (str "can't find fn " fn-sym)))
      :log-call-async
      (fn [fn-sym]
        (devtools-msg (str "call async " fn-sym)))
      :log-call
      (fn [fn-sym]
        (devtools-msg (str "call " fn-sym))))
    #(do-js-load sources)
    (fn [])
    (fn [])))

(defn handle-build-complete [runtime {:keys [info reload-info] :as msg}]
  (let [warnings
        (->> (for [{:keys [resource-name warnings] :as src} (:sources info)
                   :when (not (:from-jar src))
                   warning warnings]
               (assoc warning :resource-name resource-name))
             (distinct)
             (into []))]

    (when env/log
      (doseq [{:keys [msg line column resource-name] :as w} warnings]
        (js/console.warn (str "BUILD-WARNING in " resource-name " at [" line ":" column "]\n\t" msg))))

    (when env/autoload
      (when (or (empty? warnings) env/ignore-warnings)
        (let [sources-to-reload
              (env/filter-reload-sources info reload-info)]

          (when (seq sources-to-reload)
            (when-not (seq (get-in msg [:reload-info :after-load]))
              (devtools-msg "reloading code but no :after-load hooks are configured!"
                "https://shadow-cljs.github.io/docs/UsersGuide.html#_lifecycle_hooks"))

            (do-js-reload msg sources-to-reload)
            ))))))

;; FIXME: add something useful?
(def client-info {})

(defonce ws-was-welcome-ref (atom false))

(when (and env/enabled (pos? env/worker-client-id))

  (extend-type cljs-shared/Runtime
    api/IEvalJS
    (-js-eval [this code success fail]
      (try
        (success (js/eval code))
        (catch :default e
          (fail e))))

    cljs-shared/IHostSpecific
    (do-invoke [this ns {:keys [js] :as msg} success fail]
      ;; attempting to use actual module specific specials, instead of re-creating them
      ;; providing something generic might break code that would work when loaded via require directly
      ;; would be better if we were able to eval directly in the module context, but I'm not sure how to do that
      (try
        (success
          (let [js (str "goog.shadow$tmp = function(exports, require, module, __filename, __dirname) { return " js "};")]
            (js/eval js)

            (let [abs-file (str js/__dirname "/" (-> ns (str) (str/replace #"-" "_")) ".js")
                  mod (unchecked-get js/require.cache abs-file)]

              (js/goog.shadow$tmp
                (if mod (.-exports mod) #js {})
                (if mod (.-require mod) js/require)
                mod
                abs-file
                ;; all files always sit in the same dir, so fine to re-use this
                js/__dirname))))
        (catch :default e
          (fail e))))

    (do-repl-init [runtime {:keys [repl-sources]} done error]
      (let [sources-to-load
            (->> repl-sources
                 (remove env/src-is-loaded?)
                 (into []))]
        (do-js-load sources-to-load)
        (done)))

    (do-repl-require [runtime {:keys [sources reload-namespaces] :as msg} done error]
      (let [sources-to-load
            (->> sources
                 (remove (fn [{:keys [provides] :as src}]
                           (and (env/src-is-loaded? src)
                                (not (some reload-namespaces provides)))))
                 (into []))]

        (try
          (do-js-load sources-to-load)
          (done sources-to-load)
          (catch :default ex
            (error ex))))))

  (cljs-shared/add-plugin! ::client #{}
    (fn [{:keys [runtime] :as env}]
      (let [svc {:runtime runtime}]

        (api/add-extension runtime ::client
          {:on-welcome
           (fn []
             ;; FIXME: why does this break stuff when done when the namespace is loaded?
             ;; why does it have to wait until the websocket is connected?
             (reset! ws-was-welcome-ref true)
             (devtools-msg (str "#" (-> runtime :state-ref deref :client-id) " ready!")))

           :on-disconnect
           (fn [e]
             ;; don't show error if connection was denied
             ;; that already shows an error
             (when @ws-was-welcome-ref
               (devtools-msg "The Websocket connection was closed!")
               (reset! ws-was-welcome-ref false)
               ))

           :ops
           {:access-denied
            (fn [msg]
              (reset! ws-was-welcome-ref false))

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
            }})
        svc))

    (fn [{:keys [runtime] :as svc}]
      (api/del-extension runtime ::client)))

  (cljs-shared/init-runtime! client-info #(ws/start ws-impl %) ws/send ws/stop))
